# Design Decisions

The decision log for TradeFlow. [TradeFlow_SPEC.md](../TradeFlow_SPEC.md) remains the source of
truth for *what* the system does; this doc records *why* it is built the way it is — the options
that were on the table, what was chosen, and what that choice cost.

Two decisions get the long treatment because they shape the codebase most: **DD-09** (how tenant
context travels) and **DD-02** (how tenant state is laid out in Redis). The rest are summarised.

| ID | Decision | Detail |
|---|---|---|
| DD-01 | Modular monolith, Kafka at the seams | [↓](#dd-01--modular-monolith-over-microservices) |
| DD-02 | Flat tenant-prefixed Redis keys, **no** cluster hash tags | [↓](#dd-02--flat-tenant-prefixed-redis-keys-over-hash-tags) |
| DD-03 | Transactional outbox for trade → event | [↓](#dd-03--transactional-outbox) |
| DD-04 | `MONITOR` (async breach) default, `BLOCK` opt-in | [↓](#dd-04--monitor-vs-block) |
| DD-05 | Weighted-average cost basis, not FIFO | [↓](#dd-05--wac-over-fifo) |
| DD-06 | One Redis Lua script for update + limit + dedupe | [↓](#dd-06--one-lua-script-for-the-position-update) |
| DD-07 | Discriminator-column multi-tenancy | [↓](#dd-07--discriminator-column-multi-tenancy) |
| DD-08 | Target Java 25 for Loom | [↓](#dd-08--java-25--loom) |
| DD-09 | **`ScopedValue`** over `ThreadLocal` for tenant context | [↓](#dd-09--scopedvalue-over-threadlocal) |
| DD-10 | `StructuredTaskScope` for fan-out reads, behind one helper | [↓](#dd-10--structuredtaskscope-for-fan-out-reads) |

---

## DD-09 — `ScopedValue` over `ThreadLocal`

**Context.** `tenantId` is needed almost everywhere: every repository query is scoped by it, every
published event carries it, every log line should mention it. Threading it through as a method
parameter is correct but viral — it would appear in nearly every signature in the codebase — so it
wants to be *ambient*: bound once at the edge, readable anywhere below.

Ambient state has one catastrophic failure mode here. If request A's `tenantId` is still visible
when request B runs, firm B reads firm A's positions. Tenant isolation is the headline invariant
(FR-TEN-02), so the mechanism that carries the tenant is a security control, not a convenience.

**Decision.** Bind `tenantId` / `userId` / `traceId` as `ScopedValue`s
([`TenantContext`](../common/src/main/java/com/tradeflow/common/context/TenantContext.java)), bound
by `TenantContextFilter` on the request path and re-bound from the event payload inside Kafka
consumers.

```java
ScopedValue.where(TENANT_ID, tenantId).run(() -> chain.doFilter(req, res));
```

**Why not `ThreadLocal`.** It fails on both of the axes that matter here:

1. **It does not survive `fork()`.** The composite read behind `GET /summary` and the per-instrument
   P&L fan-out both fork subtasks inside a `StructuredTaskScope` (DD-10). `ThreadLocal` values are
   *not* inherited by the new virtual threads those forks create, so `tenantId()` inside a subtask
   would be absent — meaning either a failure on the happy path or, if some default were supplied, a
   silent read of the wrong tenant. `InheritableThreadLocal` nominally fixes this but is discouraged
   with virtual threads: it copies the inherited map per thread, and "one virtual thread per
   request" means that copy happens at a scale it was never designed for. `ScopedValue` bindings
   *are* inherited by forked subtasks by design — precisely the property the fan-out reads need
   (NFR-CONC-04).

2. **It is mutable and must be cleaned up by hand.** The `ThreadLocal` idiom is `set()` in a filter
   and `remove()` in a `finally`. Any path that misses that `remove()` leaves the previous tenant's
   id on a *pooled* thread for the next request to find — the cross-tenant leak above, produced by
   one omitted line. A `ScopedValue` binding lives only for the dynamic extent of its `run()` block;
   unbinding is the JVM's job and cannot be forgotten.

There is a third, quieter benefit: `ScopedValue` has no `set()`. Nothing deep in the call stack can
reassign the current tenant — rebinding requires a nested `where(...).run(...)` whose effect ends
with its block. The tenant is fixed for the request by construction rather than by convention.

**What it cost us.**

- **Reading an unbound value throws.** `TenantContext.tenantId()` fails loudly outside a bound
  context. That is deliberate — tenant-scoped code must never run without a tenant — but it makes
  the binding boundaries load-bearing, and tests have to establish one.
- **Non-request entry points must re-bind explicitly.** Kafka consumer threads and the Spring Batch
  job sit outside the filter's binding, so each reads `tenantId` from the event payload and re-binds
  around its processing (§11.1). With `ThreadLocal` you could sloppily `set()` and move on; here you
  must own a block. The discipline is the point.
- **You cannot set it "just for a moment" deep in a call stack.** Code that wants a different tenant
  has to restructure into a scope. In practice only the platform-admin paths ever want that.

**Verified by.** `T-SC-02` (scoped-value inheritance into forked subtasks under concurrency),
`T-ISO-01/02` (cross-tenant access → 404).

---

## DD-02 — Flat tenant-prefixed Redis keys over hash tags

**Context.** Live positions, rate-limit windows and the trade applied-set all live in Redis, and all
of them are per-tenant. Two key shapes were on the table:

```
tenant:{tenantId}:pos:{symbol}      # flat prefix — chosen
{tenant:tenantId}:pos:{symbol}      # hash-tagged
```

The braces are not cosmetic. Redis Cluster maps a key to one of 16384 slots by hashing the whole key
name — *unless* the key contains `{...}`, in which case only the text inside the braces is hashed.
Tagging by tenant therefore forces every key belonging to one tenant into a single slot, and so onto
a single node.

Note what is *not* at stake: **isolation comes from the tenant prefix, which both shapes have.** No
key is reachable without knowing the tenant id either way. What the hash tag adds is *co-location*.

**Decision.** Flat tenant-prefixed keys, no hash tags (spec §8.1). See
[`PositionStore.positionKey`](../position-store/src/main/java/com/tradeflow/position/PositionStore.java).

**Why.**

- **A whale tenant must not become a hot slot.** Tenant sizes in this domain are wildly skewed — one
  high-frequency firm can outweigh fifty small desks. Hash-tagging by tenant makes the *tenant* the
  unit of sharding, so the busiest tenant's entire working set is pinned to one node and resharding
  can never relieve it. That is a scaling ceiling with no exit. Flat keys hash by full key name, so
  one tenant's symbols spread across the keyspace and load follows.
- **Slot placement is a deployment concern, not a domain one.** A hash tag encodes "these things must
  live together" into the key format itself — the cheapest thing to write and the most expensive
  thing to change later, since it is baked into every key already in Redis.
- **We already accept noisy-neighbour risk once, at the database layer (DD-07); no reason to accept
  it again on the hot path.** Position updates are the latency-critical operation in the system.

**What it cost us — and this one is real.** Multi-key atomic operations on Redis Cluster require all
keys in the same slot. The DD-06 script touches two:

```
KEYS[1] = tenant:{id}:pos:{symbol}          -- the position
KEYS[2] = tenant:{id}:applied:{tradeId}     -- the idempotency marker
```

Under flat keys those hash to different slots, so on a *clustered* Redis the script would be
rejected with `CROSSSLOT`. TradeFlow runs single-node Redis today (one slot space), so the cost is
latent rather than live — but it is exactly why this decision needs writing down rather than
rediscovering under load.

**The migration path, if clustering is ever needed.** Do not tag by tenant. Tag by the unit the
script actually needs to be atomic over, which is tenant **×** instrument:

```
{tenant:<id>:<symbol>}:pos
{tenant:<id>:<symbol>}:applied:<tradeId>
```

Both of the script's keys then share a slot, while sharding granularity stays fine enough that a
large tenant still spreads across nodes. Rate-limit keys are single-key operations and need no tag
at all. This preserves the DD-06 atomicity guarantee without reintroducing the hot-slot ceiling.

**One more trade-off, stated honestly.** Losing a slot under flat keys degrades a thin slice of many
tenants rather than blinding one tenant completely; hash tags would give per-tenant failure
*isolation* instead. We prefer dilution here, and that is affordable only because positions are
rebuildable from Postgres (FR-POS-05).

---

## The rest

### DD-01 — Modular monolith over microservices
One deployable (`gateway`), seven Maven modules, dependencies flowing toward `common`. Anything that
would cross a future service boundary goes over **Kafka, not a direct call**, so the boundaries are
real and a later split is mechanical rather than a rewrite. Cost: the boundaries are enforced by
module structure and review, not by the network — they can be violated by adding a dependency.

### DD-03 — Transactional outbox
A trade INSERT and its `outbox` row are written in **one** database transaction; a relay polls the
outbox and publishes to Kafka. This removes the dual-write failure where a trade commits but its
event is lost (FR-TRADE-05). Cost: at-least-once delivery and a little latency — which is why every
consumer is idempotent anyway.

### DD-04 — `MONITOR` vs `BLOCK`
Default `MONITOR`: the trade is always accepted and the breach is raised asynchronously, keeping
ingestion off the risk-evaluation path. `BLOCK` rejects synchronously and must read the projected
position inline. Cost: under `MONITOR` a breaching position exists briefly before anyone is told.

### DD-05 — WAC over FIFO
Weighted-average cost basis needs no lot tracking and is fully deterministic from the trade stream,
which is what makes P&L snapshots recomputable and idempotent. FIFO is deferred to future work; it
would require per-lot state and a very different rebuild story.

### DD-06 — One Lua script for the position update
`updatePositionAndEvaluate` does dedupe check → apply delta → limit compare in a single server-side
script. Atomicity comes from **Redis's single-threaded execution, not a JVM lock** — so no
distributed lock, and virtual threads introduce no new race. Verified by `T-POS-01` (100 concurrent
buys settle to exactly the right position).

### DD-07 — Discriminator-column multi-tenancy
Shared schema with a `tenant_id` column, rather than schema- or database-per-tenant. Simplest to
operate; isolation enforced at the query layer and proven by tests. Cost, stated plainly:
noisy-neighbour risk and a shared blast radius — a missing `tenantId` predicate is a data leak, so
the isolation tests are not optional.

### DD-08 — Java 25 + Loom
Targets Java 25 (LTS) so virtual threads and scoped values are **final** APIs and structured
concurrency is available as preview. Cost: `--enable-preview` at compile *and* runtime, purely for
`StructuredTaskScope` — contained by DD-10.

### DD-10 — `StructuredTaskScope` for fan-out reads
Composite reads (`/summary`, P&L per-instrument) fork on virtual threads inside a structured scope
rather than using `CompletableFuture`, because the parent must own subtask lifetimes: fail-fast
cancellation, no leaked threads, and scoped-value inheritance (DD-09). All preview-API usage is
confined to [`Scopes`](../common/src/main/java/com/tradeflow/common/concurrent/Scopes.java), so the
churn when it finalises lands in one file. Applied **only** to bounded fan-out reads — never the
Kafka consume loop or the per-trade update, which stay sequential per partition for ordering.

---

## Smaller decisions, same reasoning

- **Cross-tenant reads return 404, never 403.** A 403 confirms the resource exists; 404 leaks
  nothing. Implemented by scoping the lookup itself, so "belongs to another tenant" and "does not
  exist" are indistinguishable by construction (`NotFoundException`, FR-TEN-02).
- **Kafka topics keyed by `tenantId`.** One choice buys per-tenant ordering (correct position math)
  *and* cross-tenant parallelism. The partition count caps tenant-level concurrency, not throughput.
- **No `synchronized` anywhere.** Blocking I/O inside a monitor pins a virtual thread to its carrier;
  `ReentrantLock` does not. JEP 491 (JDK 24+) largely moots this, but the constraint keeps the
  codebase honest about where blocking happens (NFR-CONC-05).
- **Schema is Flyway-owned, and applied migrations are immutable.** Changes go in a new `V{n}__*.sql`
  file, never as an edit — the only way a freshly built environment and a long-lived one stay
  identical.
- **Idempotency at every hop.** Trade idempotency key (24h), applied-set for trade events, unique
  `(tenant, trade_id)` for breaches, UPSERT for P&L snapshots. At-least-once delivery is assumed
  everywhere rather than hoped against.
