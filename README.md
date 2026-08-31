# TradeFlow — Multi-Tenant Trading Risk Engine

A backend platform where multiple trading firms (**tenants**) submit trades and get **real-time net
positions**, **risk-limit enforcement**, **instant breach alerts**, and **P&L reporting** — under
strict tenant isolation and concurrent load. Built as a **modular monolith** on **Java 25 + Spring
Boot 3.5**, it is a spec-driven, learning-focused portfolio project that showcases **Project Loom**
(virtual threads, scoped values, structured concurrency) as its concurrency foundation.

The single source of truth is [TradeFlow_SPEC.md](TradeFlow_SPEC.md); every feature maps to a
requirement ID (e.g. `FR-TRADE-03`) and has a traceable test (e.g. `T-POS-01`).

Why it is built the way it is — the alternatives considered and what each choice cost — is in
the **[design-decision log](docs/DESIGN_DECISIONS.md)**.

---

## Why it's interesting

- **Project Loom, used for real** — not a toy. Virtual threads per request, `ScopedValue` for tenant
  context that flows into forked work, and `StructuredTaskScope` for a fail-fast concurrent composite
  read. See [Concurrency model](#concurrency-model).
- **Correctness under concurrency, proven** — 100 concurrent trades on one instrument settle to the
  exact position (no lost updates), verified against a real Redis.
- **Event-driven with the hard parts done right** — a transactional **outbox** (no dual-write data
  loss), **at-least-once** Kafka delivery, and **idempotent** consumers.
- **Strict multi-tenancy** — every query tenant-scoped; cross-tenant access returns **404, never 403**
  (existence is never leaked), verified by isolation tests.
- **Tested against real infrastructure** — Testcontainers Postgres/Redis/Kafka + a real STOMP client;
  ~53 tests, coverage-gated on the risk-critical modules.

---

## Architecture

```
                       ┌─────────────────────────────────────────────────────────────┐
   Client ──POST /trades (JWT)──►                    gateway                           │
                       │  auth · tenant resolve (ScopedValue) · rate limit · REST · WS │
                       └───────┬─────────────────────────────────────────────┬────────┘
                               │ (in-process; module boundaries)             │
                               ▼                                             ▼
                        trade-ingestion                                  reporting
                     persist trade + outbox ──┐                    Spring Batch P&L (WAC)
                     (one transaction, DD-03) │                    + StructuredTaskScope
                               │  relay       │                       fan-out (§14.1)
                               ▼              │
                     Kafka: trade.submitted ◄─┘  (keyed by tenantId → per-tenant order)
                               │
                               ▼
                          risk-engine
              consume · atomic Redis Lua position update
              + limit eval (DD-06) · idempotent (applied-set)
                     │                         │
        position.updated                  risk.breached ──► alert-service
                                                            persist + WebSocket/STOMP push
                                                            to /topic/tenant.{id}.alerts

   State:  Postgres (trades, config, breaches, P&L, alerts, outbox)   Redis (live positions, rate limits)
```

**Modules** (dependencies flow toward `common`; cross-boundary communication is via **Kafka**, DD-01):

| Module | Responsibility |
|---|---|
| `common` | Shared event schemas (§9.2), `ScopedValue` tenant context (DD-09), `StructuredTaskScope` helper (DD-10), error body (§15) |
| `gateway` | The single deployable: security/JWT, controllers, rate limiter, WebSocket, datasource + Flyway |
| `trade-ingestion` | Validate & persist trades, transactional outbox, publish `trade.submitted` |
| `risk-engine` | Consume trades, atomic position update + limit eval, publish `position.updated`/`risk.breached` |
| `position-store` | Redis-backed atomic position state (Lua) |
| `alert-service` | Consume `risk.breached`, push per-tenant WebSocket alerts |
| `reporting` | Spring Batch P&L (WAC) + `/summary` composite read |

## Tech stack

Java 25 (LTS) · Spring Boot 3.5 · PostgreSQL 16 + Flyway · Redis 7 (Lua) · Apache Kafka (KRaft) ·
Spring Security + JWT (HS256) · Spring Batch · Spring WebSocket/STOMP · Testcontainers · Docker Compose.

---

## Concurrency model

The project's technical thesis (DD-08/09/10): **write straight-line blocking code, let Loom make it
scale and make concurrent fan-out correct.** Three pieces work as one system:

- **Virtual threads** (`spring.threads.virtual.enabled=true`) — one cheap virtual thread per request;
  blocking JDBC/Redis/Kafka calls scale without a sized thread pool (NFR-CONC-01).
- **`ScopedValue`** — tenant/user/trace context is bound immutably for the request's dynamic extent
  and **inherited by forked subtasks** (where `ThreadLocal` would not). This is why cross-tenant
  context never leaks and why `tenantId` flows into fan-out reads automatically (DD-09).
- **`StructuredTaskScope`** (JEP 505, preview) — the `GET /summary` endpoint forks three sub-reads on
  virtual threads, joins fail-fast (a failure cancels the siblings), and can't leak a thread. The
  P&L batch fans out per-instrument the same way. Isolated behind one helper in `common` (DD-10).

**Correctness, not just speed:**
- Position updates are **atomic in Redis** via a single Lua script (read-modify-write + limit check +
  idempotency dedupe in one indivisible op) — atomicity comes from Redis's single-threaded execution,
  **not JVM locks** (DD-06, §12.5). There is **no `synchronized` anywhere** in the codebase, so no
  virtual-thread pinning on the hot paths (NFR-CONC-05; also mooted by JEP 491 in JDK 24+).
- Kafka topics are **keyed by `tenantId`**, giving per-tenant ordering (correct position math) *and*
  cross-tenant parallelism from one design choice (§12.4).
- Structured concurrency is applied **only** to bounded fan-out reads — never the Kafka consume loop
  or the per-trade update (which stay sequential per partition for ordering).

## Design decisions

| ID | Decision |
|---|---|
| DD-01 | Modular monolith over microservices; module boundaries communicate via Kafka so a future split is mechanical |
| DD-02 | Flat tenant-prefixed Redis keys, **no** cluster hash tags — even slot distribution over per-tenant co-location |
| DD-03 | Transactional **outbox** — trade + event written in one DB transaction, relayed to Kafka (at-least-once, no dual-write loss) |
| DD-04 | Risk `MONITOR` (async flag) vs `BLOCK` (synchronous reject); default MONITOR |
| DD-05 | **WAC** cost basis (no lot tracking, deterministic); FIFO deferred |
| DD-06 | Single Redis **Lua** script for atomic position update + limit eval + idempotency |
| DD-07 | Discriminator-column multi-tenancy (shared schema, `tenant_id`); isolation enforced at the query layer + tests |
| DD-08 | Target **Java 25** for Loom (virtual threads final, scoped values final, structured concurrency preview) |
| DD-09 | **`ScopedValue`** over `ThreadLocal` — inherited by structured-concurrency subtasks; immutable, self-cleaning |
| DD-10 | `StructuredTaskScope` for fan-out reads (not `CompletableFuture`); isolated behind one `common` helper |

Each of these is written up in full — context, alternatives, and what the choice cost — in
**[docs/DESIGN_DECISIONS.md](docs/DESIGN_DECISIONS.md)**. DD-09 (`ScopedValue` over `ThreadLocal`)
and DD-02 (Redis key layout) get the long treatment there.

---

## Running it

**Prerequisites:** JDK 25, Maven 3.9.x, Docker + Docker Compose.

```bash
# build everything and run tests (Testcontainers spins up Postgres/Redis/Kafka)
mvn clean verify

# build the app jar, then bring up the full stack
mvn clean package && docker compose up --build

# with debug UIs (kafka-ui :8081, redis-commander :8082)
docker compose --profile tools up
```

- Health: `http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Seed a demo tenant + trades: `./scripts/seed.sh` (after the stack is up)

> `--enable-preview` is required (compile + runtime) only because structured concurrency is a preview
> API in Java 25; it's configured in the parent POM and the Dockerfile. Virtual threads and scoped
> values are final and need no flag.

## API overview (base `/api/v1`)

| Endpoint | Purpose |
|---|---|
| `POST /auth/login` | Tenant-aware login → JWT |
| `POST /admin/tenants`, `.../{id}/users` | Platform-admin onboarding (X-Platform-Admin-Key) |
| `POST/GET/PATCH /instruments` | Instrument + limit config (RISK_MANAGER/ADMIN) |
| `POST/GET /trades` | Submit (Idempotency-Key) / query trades |
| `GET /positions`, `/positions/{symbol}` | Live net positions (Redis) |
| `GET /risk/breaches` | Breach history |
| `GET /alerts` + `WS /ws` → `/topic/tenant.{id}.alerts` | Alert history + live push |
| `GET /summary` | Composite view via `StructuredTaskScope` |
| `POST /reports/pnl/run` (ADMIN), `GET /reports/pnl?date=` | Run / read P&L snapshots |

## Testing

Test pyramid (§17): unit (JUnit 5 + Mockito, e.g. the pure WAC `PnlCalculator`), slice, and
**integration against real infrastructure** via Testcontainers (Postgres/Redis/Kafka) + a real STOMP
client. Notable requirement-traceable tests:

- `T-ISO-01/02` — tenant isolation; cross-tenant → 404
- `T-POS-01` — 100 concurrent buys → exactly the right position (no lost updates)
- `T-IDEM-01` — redelivered event applied once
- `T-SC-01/02` — structured-concurrency fail-fast + scoped-value inheritance under concurrency
- `T-PNL-01..06`, `T-PNL-IDEM` — WAC P&L scenarios + idempotent batch re-run
- `T-ALERT-01`, `T-RATE-01` — per-tenant alert delivery; 429 on limit

Line coverage on `risk-engine` + `position-store` is CI-gated at ≥80% (NFR-TEST-01) via JaCoCo.

---

*Spec-driven: see [TradeFlow_SPEC.md](TradeFlow_SPEC.md). Update the spec before changing behavior.*
