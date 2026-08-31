# TradeFlow — Technical & Functional Specification

> **Document type:** Spec-Driven Development (SDD) master specification
> **Project:** TradeFlow — Multi-Tenant Trading Risk Engine
> **Version:** 1.1
> **Status:** Draft for implementation
> **Owner:** Hitarth Kotecha
>
> **Changelog 1.1:** Adopted Project Loom as the concurrency foundation — targets Java 25 (LTS); virtual threads for request/background I/O; structured concurrency (`StructuredTaskScope`) for composite reads and P&L fan-out; scoped values replace `ThreadLocal` for tenant context. See DD-08/09/10, §12, FR-SUM-*, NFR-CONC-*.

---

## How to use this document

This is the **single source of truth** for TradeFlow. Every feature, API, schema, and behavior is defined here before code is written. Each requirement has a stable ID (e.g. `FR-TRADE-03`) so commits, tests, and PRs can reference exactly what they implement.

**Rules of spec-driven development for this project:**

1. No code is merged unless it maps to a requirement ID in this spec.
2. If reality forces a change, update the spec **first**, then the code.
3. Every functional requirement has at least one acceptance criterion and one automated test.
4. AI coding agents should be given the relevant section (not the whole doc) plus the requirement IDs to implement.

---

## Table of Contents

1. [Vision & Scope](#1-vision--scope)
2. [Goals and Non-Goals](#2-goals-and-non-goals)
3. [Glossary & Domain Model](#3-glossary--domain-model)
4. [System Architecture](#4-system-architecture)
5. [Functional Requirements](#5-functional-requirements)
6. [Non-Functional Requirements](#6-non-functional-requirements)
7. [Data Model & Database Schema](#7-data-model--database-schema)
8. [Redis Design](#8-redis-design)
9. [Event / Kafka Design](#9-event--kafka-design)
10. [API Specification](#10-api-specification)
11. [Multi-Tenancy & Security](#11-multi-tenancy--security)
12. [Concurrency Design](#12-concurrency-design)
13. [Risk Engine Logic](#13-risk-engine-logic)
14. [P&L & Reporting](#14-pnl--reporting)
15. [Error Handling & Status Codes](#15-error-handling--status-codes)
16. [Observability](#16-observability)
17. [Testing Strategy](#17-testing-strategy)
18. [Deployment & Infrastructure](#18-deployment--infrastructure)
19. [Implementation Roadmap](#19-implementation-roadmap)
20. [Open Questions & Future Work](#20-open-questions--future-work)

---

## 1. Vision & Scope

### 1.1 Problem statement

Trading firms need to submit trades, track their net positions in real time, enforce risk limits, and be alerted the instant a limit is breached. A platform serving multiple firms must keep each firm's data and configuration completely isolated.

### 1.2 What TradeFlow is

TradeFlow is a backend platform where multiple **tenants** (trading firms) onboard, submit **trades**, and have the system:

- validate and persist each trade with a full audit trail,
- maintain real-time **net positions** per instrument per tenant,
- enforce per-tenant **position limits** and reject/flag breaches,
- emit **real-time alerts** when limits are breached,
- generate periodic **P&L snapshots** and reports,
- enforce per-tenant **API rate limits**,

all while guaranteeing strict tenant isolation.

### 1.3 Why it exists (project intent)

This is a portfolio-grade system intended to demonstrate, in a single coherent codebase: multi-tenancy, event-driven architecture, caching, concurrency, batch processing, real-time delivery, and production-grade testing. It mirrors a simplified CTRM (Commodity Trading & Risk Management) platform.

---

## 2. Goals and Non-Goals

### 2.1 Goals

- **G1** — Correct, isolated multi-tenant behavior under concurrent load.
- **G2** — Real-time position tracking accurate to every committed trade.
- **G3** — Risk breach detection and alerting within 2 seconds of trade ingestion (p95).
- **G4** — Deterministic, repeatable P&L computation.
- **G5** — A fully containerized local environment startable with one command.
- **G6** — High automated test coverage on risk and position logic (≥ 80% line coverage on those modules).

### 2.2 Non-Goals (explicitly out of scope for v1)

- **NG1** — Real market data feeds / live pricing. Prices are supplied with trades or via a mock price service.
- **NG2** — A production-grade UI. A minimal demo client is acceptable but not required.
- **NG3** — Real money, settlement, or clearing integration.
- **NG4** — Regulatory reporting formats (e.g. EMIR/MiFID).
- **NG5** — Horizontal auto-scaling / Kubernetes. Docker Compose is the target.
- **NG6** — Options/derivatives pricing models (Black-Scholes etc.). Only linear instruments in v1.

---

## 3. Glossary & Domain Model

### 3.1 Glossary

| Term | Definition |
|---|---|
| **Tenant** | A trading firm onboarded onto the platform. Hard isolation boundary. |
| **User** | A person belonging to exactly one tenant. Has a role (TRADER, RISK_MANAGER, ADMIN). |
| **Instrument** | A tradable asset identified by a symbol (e.g. `CRUDE-OIL`, `NAT-GAS`). |
| **Trade** | A single buy or sell of an instrument: side, quantity, price, timestamp. |
| **Position** | Net signed quantity held by a tenant in an instrument (BUY adds, SELL subtracts). |
| **Position Limit** | Maximum absolute net quantity a tenant may hold in an instrument. |
| **Risk Breach** | Event when a trade causes (or would cause) a position to exceed its limit. |
| **P&L** | Profit and Loss — realized (from closed quantity) and unrealized (mark-to-market). |
| **Mark Price** | The reference price used to value open positions for unrealized P&L. |
| **Snapshot** | A point-in-time computed P&L record persisted for reporting. |

### 3.2 Core entities

```
Tenant 1───* User
Tenant 1───* Instrument (tenant-scoped configuration & limits)
Tenant 1───* Trade
Tenant 1───* Position        (1 per instrument, derived/maintained)
Tenant 1───* PositionLimit   (1 per instrument)
Tenant 1───* RiskBreach
Tenant 1───* PnlSnapshot
```

### 3.3 Position direction convention

- A **BUY** of quantity `q` increases net position by `+q`.
- A **SELL** of quantity `q` decreases net position by `-q`.
- Position may be negative (short).
- The **limit** is on `abs(netPosition)`.

---

## 4. System Architecture

### 4.1 Service decomposition

For v1, TradeFlow is a **modular monolith** with clearly separated modules that *could* be split into microservices later. This keeps the project buildable solo while still demonstrating service boundaries. Communication between modules that would cross a future service boundary goes through **Kafka**, not direct method calls.

> **Design decision DD-01:** Modular monolith over microservices for v1. Rationale: demonstrates event-driven boundaries without operational overhead of N deployables. The Kafka boundary makes a future split mechanical.

**Modules:**

| Module | Responsibility |
|---|---|
| `gateway` | Auth, tenant resolution, request validation, rate limiting, REST controllers |
| `trade-ingestion` | Validate, persist trades, publish `TradeSubmittedEvent` |
| `risk-engine` | Consume trade events, update positions, evaluate limits, publish breach events |
| `position-store` | Redis-backed real-time position state |
| `alert-service` | Consume breach events, deliver real-time alerts (WebSocket/SSE) |
| `reporting` | Spring Batch P&L jobs, report query APIs |
| `common` | Shared DTOs, tenant context, event schemas, exceptions |

### 4.2 High-level flow

```
Client
  │  POST /api/v1/trades  (JWT)
  ▼
[gateway] ── auth + tenant resolve + rate limit + validate
  │
  ▼
[trade-ingestion] ── persist Trade (Postgres) ── publish TradeSubmittedEvent ──► Kafka(trade.submitted)
                                                                                    │
                                                                                    ▼
                                                                            [risk-engine]
                                                                              ├─ update position (Redis, atomic)
                                                                              ├─ evaluate limit
                                                                              ├─ publish PositionUpdatedEvent ──► Kafka(position.updated)
                                                                              └─ if breach: publish RiskBreachedEvent ──► Kafka(risk.breached)
                                                                                                                            │
                                                                                                                            ▼
                                                                                                                    [alert-service]
                                                                                                                      └─ push WebSocket alert
[reporting] ── nightly Spring Batch ── read trades + mark prices ── compute P&L ── write PnlSnapshot (Postgres)
```

### 4.3 Technology choices

| Concern | Technology | Version (target) |
|---|---|---|
| Language | Java | **25 (LTS)** |
| Concurrency model | Virtual Threads (final, JEP 444) + Structured Concurrency (preview, JEP 505) + Scoped Values (final, JEP 506) | — |
| Framework | Spring Boot | 3.4.x+ (must support Java 25 + virtual threads) |
| Build | Maven | 3.9.x |
| Messaging | Apache Kafka | 3.6.x |
| Cache / state | Redis | 7.x |
| Relational DB | PostgreSQL | 16.x |
| Migrations | Flyway | latest |
| Auth | Spring Security + JWT (jjwt or Nimbus) | — |
| Batch | Spring Batch | bundled with Boot |
| Real-time | Spring WebSocket (STOMP) or SSE | — |
| Testing | JUnit 5, Mockito, Testcontainers, Awaitility | — |
| Containerization | Docker + Docker Compose | — |
| API docs | springdoc-openapi (Swagger UI) | latest |

> **Design decision DD-08 (Java 25 + Loom):** The project targets **Java 25 (LTS)** specifically to use Project Loom features:
> - **Virtual threads** (finalized in Java 21) — one cheap thread per task for I/O-bound work; no thread-pool tuning.
> - **Scoped Values** (finalized in Java 25, JEP 506) — the modern, immutable replacement for `ThreadLocal`. Critically, scoped values are **inherited by subtasks forked inside a structured-concurrency scope**, whereas `ThreadLocal` is not. This is why `TenantContext` is built on `ScopedValue` (see §11.1).
> - **Structured concurrency** (still **preview** in Java 25 via JEP 505; finalization expected ~Java 27) — fan-out/fan-in with guaranteed subtask completion, fail-fast cancellation, and readable thread dumps.
>
> **Preview flag:** Because structured concurrency is a preview API, the build and runtime must enable preview features (`--enable-preview` / `<compilerArgs>--enable-preview</compilerArgs>` and `--release 25`). This is acceptable for a portfolio project; the trade-off and the API-stability risk are tracked in §20. Virtual threads and scoped values are **final** and need no flag.

---

## 5. Functional Requirements

> Format: each requirement has an ID, a description, and acceptance criteria (AC). Priority: **M** = Must, **S** = Should, **C** = Could.

### 5.1 Tenant & User Management

| ID | Pri | Requirement |
|---|---|---|
| FR-TEN-01 | M | The system shall onboard a new tenant via an admin API, generating a unique immutable `tenantId`. |
| FR-TEN-02 | M | Each tenant shall be fully isolated: no API call may read or write another tenant's trades, positions, limits, or reports. |
| FR-TEN-03 | M | The system shall support users belonging to exactly one tenant, each with a role in {TRADER, RISK_MANAGER, ADMIN}. |
| FR-TEN-04 | M | The system shall issue a JWT on login carrying `tenantId`, `userId`, and `role`. |
| FR-TEN-05 | S | A tenant shall be deactivatable; deactivated tenants reject all non-admin API calls with 403. |

**Acceptance criteria — FR-TEN-02:**
- Given tenant A and tenant B each with one trade, when a user of tenant A queries `GET /api/v1/trades`, the response contains only tenant A's trade.
- Any attempt to access a resource by ID belonging to another tenant returns `404` (not `403`, to avoid leaking existence).

### 5.2 Instrument & Limit Configuration

| ID | Pri | Requirement |
|---|---|---|
| FR-INST-01 | M | A RISK_MANAGER or ADMIN shall register instruments for their tenant with a unique `symbol`. |
| FR-INST-02 | M | A RISK_MANAGER or ADMIN shall set a `positionLimit` (absolute max net quantity) per instrument. |
| FR-INST-03 | M | A trade referencing an unregistered instrument shall be rejected with a validation error. |
| FR-INST-04 | S | Position limits shall be updatable; updates apply to future trades only (existing positions are not retroactively rejected but may immediately register as breached). |
| FR-INST-05 | C | The system shall support a per-instrument `markPrice` settable via API for unrealized P&L. |

### 5.3 Trade Ingestion

| ID | Pri | Requirement |
|---|---|---|
| FR-TRADE-01 | M | A TRADER shall submit a trade with: `instrumentSymbol`, `side` (BUY/SELL), `quantity` (> 0), `price` (> 0). |
| FR-TRADE-02 | M | The system shall reject trades failing validation (see §5.3.1) with `400` and a structured error body. |
| FR-TRADE-03 | M | A valid trade shall be persisted with status `ACCEPTED`, a server-generated `tradeId` (UUID), `tenantId`, `userId`, and `submittedAt` (UTC, OffsetDateTime). |
| FR-TRADE-04 | M | On successful persistence, the system shall publish a `TradeSubmittedEvent` to Kafka topic `trade.submitted`. |
| FR-TRADE-05 | M | Trade persistence and event publication shall be reliable: a trade is never persisted without an event eventually being published (see DD-03 outbox note). |
| FR-TRADE-06 | S | Each trade shall carry an idempotency key (`Idempotency-Key` header); duplicate keys within 24h return the original trade without creating a new one. |
| FR-TRADE-07 | S | Trades shall be queryable with filters: instrument, side, date range, with pagination. |

#### 5.3.1 Trade validation rules

| Rule | Condition | Error code |
|---|---|---|
| V1 | `quantity` is a positive integer | `INVALID_QUANTITY` |
| V2 | `price` is a positive decimal with ≤ 4 decimal places | `INVALID_PRICE` |
| V3 | `side` ∈ {BUY, SELL} | `INVALID_SIDE` |
| V4 | `instrumentSymbol` exists for this tenant | `UNKNOWN_INSTRUMENT` |
| V5 | `quantity` ≤ configured per-trade max (default 1,000,000) | `QUANTITY_TOO_LARGE` |

> **Design decision DD-03 (outbox):** To satisfy FR-TRADE-05, the trade INSERT and an `outbox` row are written in the **same transaction**; a relay polls the outbox and publishes to Kafka, marking rows dispatched. This guarantees at-least-once delivery without distributed transactions. (For v1 a simpler "publish after commit with retry" is acceptable if explicitly noted, but the outbox is the target.)

### 5.4 Position Tracking

| ID | Pri | Requirement |
|---|---|---|
| FR-POS-01 | M | The risk-engine shall consume `trade.submitted` and update the tenant's net position for the instrument atomically. |
| FR-POS-02 | M | Position state shall be stored in Redis keyed per tenant+instrument and shall reflect every committed trade. |
| FR-POS-03 | M | Concurrent trades on the same tenant+instrument shall not lose updates (atomic increment/decrement). |
| FR-POS-04 | M | The system shall expose `GET /api/v1/positions` returning current net positions for the tenant. |
| FR-POS-05 | S | Positions shall be reconstructable from the trade table (Postgres) if Redis is flushed (rebuild job). |

**Acceptance criteria — FR-POS-03:**
- Given an instrument at position 0, when 100 concurrent BUY trades of quantity 10 are submitted, the final net position is exactly `1000` (no lost updates). Verified by a Testcontainers + concurrency test using a `CountDownLatch`.

### 5.5 Risk Engine

| ID | Pri | Requirement |
|---|---|---|
| FR-RISK-01 | M | After each position update, the engine shall evaluate `abs(newNetPosition)` against the instrument's limit. |
| FR-RISK-02 | M | If the limit is breached, the engine shall publish a `RiskBreachedEvent` to `risk.breached` and persist a `RiskBreach` record. |
| FR-RISK-03 | M | Breach evaluation shall be idempotent: re-processing the same trade event shall not create duplicate breach records (dedupe by `tradeId`). |
| FR-RISK-04 | S | The system shall support two modes per tenant: `BLOCK` (reject the breaching trade at ingestion) or `MONITOR` (accept but flag). Default `MONITOR`. |
| FR-RISK-05 | M | A `GET /api/v1/risk/breaches` API shall list breaches for the tenant with pagination. |

> **Design decision DD-04 (BLOCK vs MONITOR):** In `MONITOR` mode the trade is always accepted and the breach is asynchronous (event-driven). In `BLOCK` mode the ingestion path must synchronously check the projected position before accepting — this requires a fast Redis read of current position in the gateway/ingestion path. v1 default is `MONITOR` to keep the ingestion path async and simple; `BLOCK` is a Should.

### 5.6 Alerting

| ID | Pri | Requirement |
|---|---|---|
| FR-ALERT-01 | M | The alert-service shall consume `risk.breached` and push a real-time alert to connected clients of the affected tenant only. |
| FR-ALERT-02 | M | Real-time transport shall be WebSocket (STOMP) with a per-tenant topic, or SSE per-tenant stream. |
| FR-ALERT-03 | M | A client shall only receive alerts for its own tenant (authorization enforced on subscription). |
| FR-ALERT-04 | S | Breach alerts shall also be persisted so a client connecting later can fetch missed alerts via `GET /api/v1/alerts`. |
| FR-ALERT-05 | C | The system shall support an email alert channel (mock SMTP / logged) as an alternative delivery. |

### 5.7 Rate Limiting

| ID | Pri | Requirement |
|---|---|---|
| FR-RATE-01 | M | The gateway shall enforce a per-tenant request rate limit using a Redis sliding-window algorithm. |
| FR-RATE-02 | M | When the limit is exceeded, the API shall return `429` with a `Retry-After` header. |
| FR-RATE-03 | S | Rate limits shall be configurable per tenant (default: 100 requests / 10 seconds). |
| FR-RATE-04 | S | Rate limit counters shall be isolated per tenant (one tenant cannot exhaust another's quota). |

### 5.8 P&L & Reporting

| ID | Pri | Requirement |
|---|---|---|
| FR-PNL-01 | M | A Spring Batch job shall compute P&L per tenant per instrument on a schedule (nightly) and on-demand. |
| FR-PNL-02 | M | Realized P&L shall be computed from matched buy/sell quantities; unrealized P&L from open position × (markPrice − avgCost). |
| FR-PNL-03 | M | Results shall be written to `pnl_snapshots` with `snapshotDate`, `tenantId`, `instrumentSymbol`, `realizedPnl`, `unrealizedPnl`. |
| FR-PNL-04 | M | `GET /api/v1/reports/pnl?date=YYYY-MM-DD` shall return the snapshot for a tenant with pagination. |
| FR-PNL-05 | S | The batch job shall be restartable and idempotent: re-running for the same date overwrites that date's snapshot rather than duplicating. |
| FR-PNL-06 | C | A `GET /api/v1/reports/pnl/range` shall return a time series of daily P&L. |

#### 5.8.1 P&L calculation method

- **Cost basis:** Weighted-average cost (WAC) per instrument. (Document this; FIFO is an alternative future option.)
- **Realized P&L** accrues when a trade reduces an existing opposite position (a closing trade).
- **Unrealized P&L** = `openQuantity × (markPrice − weightedAvgCost)`, sign-aware for long/short.

> **Design decision DD-05:** WAC chosen over FIFO for v1 because it requires no lot-tracking and is deterministic. FIFO is listed under future work.

### 5.9 Account Summary (Structured-Concurrency Showcase)

| ID | Pri | Requirement |
|---|---|---|
| FR-SUM-01 | S | The system shall expose `GET /api/v1/summary` returning a composite view for the tenant: current positions, recent breaches, and latest P&L snapshot, fetched concurrently. |
| FR-SUM-02 | S | The three sub-reads shall be executed as subtasks within a single **structured-concurrency scope** that forks one virtual thread per sub-read and joins all results. |
| FR-SUM-03 | S | If any sub-read fails, the scope shall cancel the remaining sub-reads (fail-fast) and the endpoint shall return `500` with a structured error; no subtask thread may leak. |
| FR-SUM-04 | S | Each forked sub-read shall observe the same `tenantId` via the inherited `ScopedValue` tenant context (no manual passing of tenant id into subtasks). |

**Acceptance criteria — FR-SUM-02/03/04:**
- The handler opens a `StructuredTaskScope`, forks three subtasks (positions, breaches, P&L), and `join()`s once.
- A forced failure in one subtask cancels the others (verified by asserting the others are interrupted/unavailable) and surfaces as a single error.
- Each subtask reads the correct `tenantId` purely from the inherited scoped value — verified by a test that runs two tenants concurrently and asserts no cross-contamination.



| ID | Category | Requirement | Target |
|---|---|---|---|
| NFR-PERF-01 | Latency | Trade submission API p95 response time | < 150 ms (excluding async risk processing) |
| NFR-PERF-02 | Latency | Breach alert delivered after trade ingestion (p95) | < 2 s end-to-end |
| NFR-PERF-03 | Throughput | Sustained trade ingestion | ≥ 200 trades/sec on a dev laptop |
| NFR-REL-01 | Reliability | Trade→event delivery | at-least-once, no trade lost |
| NFR-REL-02 | Reliability | Risk processing | idempotent; safe to replay Kafka |
| NFR-SEC-01 | Security | All non-auth endpoints require a valid JWT | enforced |
| NFR-SEC-02 | Security | Passwords stored with BCrypt; no plaintext secrets in repo | enforced |
| NFR-SEC-03 | Security | Tenant isolation verified by automated tests | covered |
| NFR-DATA-01 | Consistency | Positions in Redis must be reconstructable from Postgres | rebuild job exists |
| NFR-CONC-01 | Concurrency | HTTP request handling shall run on virtual threads (one virtual thread per request) | enabled |
| NFR-CONC-02 | Concurrency | Background/fan-out executors shall use virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`), never a fixed platform-thread pool, and virtual threads shall never be pooled or reused | enforced |
| NFR-CONC-03 | Concurrency | Multi-source read aggregation (account summary, bulk position read, P&L compute) shall use structured concurrency (`StructuredTaskScope`) with fail-fast joining and no thread leaks | enforced |
| NFR-CONC-04 | Concurrency | Cross-cutting request context (tenantId, userId, traceId) shall propagate to forked subtasks via `ScopedValue`, not `ThreadLocal` | enforced |
| NFR-CONC-05 | Concurrency | Code shall avoid virtual-thread pinning: no `synchronized` around blocking I/O (use `ReentrantLock` if locking is needed); rely on JDK 25 reduced-pinning behavior (JEP 491) | reviewed |
| NFR-TEST-01 | Quality | Line coverage on risk-engine + position modules | ≥ 80% |
| NFR-TEST-02 | Quality | Integration tests run against real Postgres/Redis/Kafka via Testcontainers | yes |
| NFR-OBS-01 | Observability | Structured logging with `tenantId` + `tradeId` in MDC | yes |
| NFR-OBS-02 | Observability | Health + metrics endpoints exposed (Actuator) | yes |
| NFR-PORT-01 | Portability | Whole stack starts with `docker compose up` | yes |
| NFR-DOC-01 | Docs | OpenAPI/Swagger UI available for all endpoints | yes |

---

## 7. Data Model & Database Schema

> Migrations managed by Flyway (`V1__init.sql`, etc.). All timestamps are `TIMESTAMPTZ` (stored UTC; mapped to `OffsetDateTime` in Java). All tenant-scoped tables carry `tenant_id` and are indexed on it.

### 7.1 `tenants`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | tenant id |
| name | VARCHAR(120) | unique |
| status | VARCHAR(16) | ACTIVE / INACTIVE |
| risk_mode | VARCHAR(16) | BLOCK / MONITOR (default MONITOR) |
| rate_limit_per_window | INT | default 100 |
| rate_window_seconds | INT | default 10 |
| created_at | TIMESTAMPTZ | |

### 7.2 `users`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| tenant_id | UUID FK → tenants.id | indexed |
| email | VARCHAR(160) | unique within tenant |
| password_hash | VARCHAR(100) | BCrypt |
| role | VARCHAR(16) | TRADER / RISK_MANAGER / ADMIN |
| created_at | TIMESTAMPTZ | |

Unique index: `(tenant_id, email)`.

### 7.3 `instruments`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| tenant_id | UUID FK | indexed |
| symbol | VARCHAR(40) | |
| position_limit | BIGINT | absolute max net qty |
| mark_price | NUMERIC(18,4) | nullable |
| created_at | TIMESTAMPTZ | |

Unique index: `(tenant_id, symbol)`.

### 7.4 `trades`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | tradeId |
| tenant_id | UUID FK | indexed |
| user_id | UUID FK | |
| instrument_symbol | VARCHAR(40) | |
| side | VARCHAR(4) | BUY / SELL |
| quantity | BIGINT | > 0 |
| price | NUMERIC(18,4) | > 0 |
| status | VARCHAR(16) | ACCEPTED / REJECTED |
| idempotency_key | VARCHAR(80) | nullable, unique within tenant |
| submitted_at | TIMESTAMPTZ | |

Indexes: `(tenant_id, instrument_symbol)`, `(tenant_id, submitted_at)`, unique `(tenant_id, idempotency_key)`.

### 7.5 `outbox`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| aggregate_type | VARCHAR(40) | e.g. TRADE |
| aggregate_id | UUID | tradeId |
| topic | VARCHAR(60) | trade.submitted |
| payload | JSONB | event body |
| status | VARCHAR(16) | PENDING / DISPATCHED |
| created_at | TIMESTAMPTZ | |
| dispatched_at | TIMESTAMPTZ | nullable |

### 7.6 `risk_breaches`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| tenant_id | UUID FK | indexed |
| trade_id | UUID | dedupe key |
| instrument_symbol | VARCHAR(40) | |
| net_position | BIGINT | position at breach |
| position_limit | BIGINT | limit at breach |
| detected_at | TIMESTAMPTZ | |

Unique index: `(tenant_id, trade_id)` for idempotency.

### 7.7 `pnl_snapshots`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| tenant_id | UUID FK | indexed |
| snapshot_date | DATE | |
| instrument_symbol | VARCHAR(40) | |
| realized_pnl | NUMERIC(20,4) | |
| unrealized_pnl | NUMERIC(20,4) | |
| net_position | BIGINT | |
| avg_cost | NUMERIC(18,4) | |
| created_at | TIMESTAMPTZ | |

Unique index: `(tenant_id, snapshot_date, instrument_symbol)` (supports idempotent re-run / upsert).

### 7.8 `alerts`

| Column | Type | Notes |
|---|---|---|
| id | UUID PK | |
| tenant_id | UUID FK | indexed |
| type | VARCHAR(30) | RISK_BREACH |
| message | TEXT | |
| payload | JSONB | |
| created_at | TIMESTAMPTZ | |
| read_at | TIMESTAMPTZ | nullable |

---

## 8. Redis Design

### 8.1 Key namespacing (tenant isolation)

All keys are prefixed with the tenant id. **No Redis cluster hash tags** are used to avoid slot hot-spotting; a flat prefix strategy is used.

| Purpose | Key pattern | Type | Notes |
|---|---|---|---|
| Net position | `tenant:{tenantId}:pos:{symbol}` | String (integer) | atomic `INCRBY`/`DECRBY` |
| Avg cost (for live P&L) | `tenant:{tenantId}:cost:{symbol}` | Hash {qty, avgCost} | updated under lock/script |
| Rate limit window | `tenant:{tenantId}:rl:{windowKey}` | Sorted Set | sliding window timestamps |
| Breach dedupe (short TTL) | `tenant:{tenantId}:breach:{tradeId}` | String | TTL 1h, SETNX |
| Alert subscription auth cache | `tenant:{tenantId}:user:{userId}` | String | optional |

> **Design decision DD-02 (flat keys, no hash tags):** Redis Cluster hashes the whole key name to a slot unless the key contains `{...}`, in which case only the braced text is hashed. Tagging by tenant would pin all of one tenant's keys to a single slot — co-locating them for multi-key ops, but making the busiest tenant an unsplittable hot slot that resharding cannot relieve. Flat prefixes distribute a tenant's symbols across the keyspace instead. Isolation is unaffected either way (it comes from the prefix, present in both). Known cost: the DD-06 script spans two keys (`pos` and `applied`), so on a clustered Redis it would fail `CROSSSLOT`; the migration path is to tag by tenant **×** instrument (`{tenant:<id>:<symbol>}:...`), not by tenant. See docs/DESIGN_DECISIONS.md.

### 8.2 Atomicity

- Position updates use `INCRBY`/`DECRBY` (atomic) — satisfies FR-POS-03.
- Multi-step updates (position + avg cost + breach check) that must be atomic together use a **Lua script** executed server-side, so the read-modify-write is a single atomic operation. This is the recommended approach for the risk-engine's core update.

> **Design decision DD-06:** A single Lua script `updatePositionAndEvaluate` performs: read current position, apply delta, write new position, compare against limit (passed as arg), and return `{newPosition, breached}`. This eliminates race conditions across the position update and limit check without distributed locks.

### 8.3 Sliding-window rate limiter (algorithm)

For each request:
1. `now = currentTimeMillis()`
2. `ZREMRANGEBYSCORE key 0 (now - windowMs)` — drop old entries
3. `ZCARD key` — count requests in window
4. If count ≥ limit → reject (429)
5. Else `ZADD key now uuid` and `EXPIRE key windowSeconds`

Steps 2–5 wrapped in a Lua script for atomicity.

---

## 9. Event / Kafka Design

### 9.1 Topics

| Topic | Partitions | Key | Producer | Consumers |
|---|---|---|---|---|
| `trade.submitted` | 6 | `tenantId` | trade-ingestion | risk-engine |
| `position.updated` | 6 | `tenantId` | risk-engine | reporting (optional), audit |
| `risk.breached` | 3 | `tenantId` | risk-engine | alert-service |

> **Partitioning by `tenantId`** guarantees per-tenant ordering and lets the same tenant's trades be processed by one consumer thread in order — important for position correctness. Trades for the *same instrument within a tenant* are therefore ordered.

### 9.2 Event schemas (JSON)

**TradeSubmittedEvent** (`trade.submitted`)
```json
{
  "eventId": "uuid",
  "eventType": "TradeSubmitted",
  "occurredAt": "2026-06-27T10:15:30.123Z",
  "tenantId": "uuid",
  "tradeId": "uuid",
  "instrumentSymbol": "CRUDE-OIL",
  "side": "BUY",
  "quantity": 100,
  "price": 72.5500
}
```

**PositionUpdatedEvent** (`position.updated`)
```json
{
  "eventId": "uuid",
  "eventType": "PositionUpdated",
  "occurredAt": "2026-06-27T10:15:30.456Z",
  "tenantId": "uuid",
  "instrumentSymbol": "CRUDE-OIL",
  "netPosition": 100,
  "triggeringTradeId": "uuid"
}
```

**RiskBreachedEvent** (`risk.breached`)
```json
{
  "eventId": "uuid",
  "eventType": "RiskBreached",
  "occurredAt": "2026-06-27T10:15:30.789Z",
  "tenantId": "uuid",
  "instrumentSymbol": "CRUDE-OIL",
  "netPosition": 11000,
  "positionLimit": 10000,
  "triggeringTradeId": "uuid"
}
```

### 9.3 Delivery & idempotency

- Consumers commit offsets **after** successful processing (at-least-once).
- All consumers are **idempotent**: keyed dedupe (`tradeId` for breaches; position updates are naturally idempotent only if guarded — see note).
- **Position update idempotency note:** raw `INCRBY` is *not* idempotent on redelivery. To handle redelivery safely, the engine records processed `tradeId`s (Redis `SETNX tenant:{id}:applied:{tradeId}` with TTL, or a processed-events table) and skips already-applied trades. This is **required** (FR-POS / NFR-REL-02).

### 9.4 Dead-letter handling

- A consumer that fails processing after N retries publishes the message to `<topic>.DLT`.
- DLT messages are logged and queryable for debugging.

---

## 10. API Specification

> Base path: `/api/v1`. All endpoints (except `/auth/login` and tenant onboarding admin endpoint) require `Authorization: Bearer <JWT>`. All responses are JSON. Errors follow §15.

### 10.1 Auth

**POST `/auth/login`**
Request:
```json
{ "tenantName": "Acme Trading", "email": "trader@acme.com", "password": "secret" }
```
Response `200`:
```json
{ "token": "<jwt>", "expiresIn": 3600, "role": "TRADER", "tenantId": "uuid" }
```

> **Change (Phase 1):** login is **tenant-aware** — the request carries `tenantName` alongside `email`/`password`. Rationale: email is unique only per tenant (§7.2), so an email alone cannot identify a single user across tenants; requiring the tenant identifier matches real multi-tenant SaaS login and needs no schema change. All failure modes (unknown tenant, unknown email, wrong password) return the same `401 UNAUTHORIZED` to avoid leaking which part failed.

### 10.2 Tenant onboarding (platform admin)

**POST `/admin/tenants`** (requires platform-admin credential)
Request:
```json
{ "name": "Acme Trading", "riskMode": "MONITOR" }
```
Response `201`: tenant object with `id`.

### 10.3 Instruments

**POST `/instruments`** (RISK_MANAGER/ADMIN)
```json
{ "symbol": "CRUDE-OIL", "positionLimit": 10000, "markPrice": 72.5 }
```
Response `201`.

**GET `/instruments`** → list for tenant.

**PATCH `/instruments/{symbol}`** → update limit / mark price.

### 10.4 Trades

**POST `/trades`** (TRADER) — headers: `Idempotency-Key` (optional)
```json
{ "instrumentSymbol": "CRUDE-OIL", "side": "BUY", "quantity": 100, "price": 72.55 }
```
Response `201`:
```json
{
  "tradeId": "uuid",
  "status": "ACCEPTED",
  "instrumentSymbol": "CRUDE-OIL",
  "side": "BUY",
  "quantity": 100,
  "price": 72.55,
  "submittedAt": "2026-06-27T10:15:30.123Z"
}
```
Errors: `400` (validation), `404` (unknown instrument), `409` (BLOCK-mode breach), `429` (rate limited).

**GET `/trades`** — query params: `instrument`, `side`, `from`, `to`, `page`, `size`. Paginated.

**GET `/trades/{tradeId}`** — single trade (404 if not in tenant).

### 10.5 Positions

**GET `/positions`** → all current net positions for tenant.
```json
[
  { "instrumentSymbol": "CRUDE-OIL", "netPosition": 100, "positionLimit": 10000, "utilizationPct": 1.0 }
]
```

**GET `/positions/{symbol}`** → single instrument position.

### 10.6 Risk

**GET `/risk/breaches`** — paginated breach history (RISK_MANAGER/ADMIN).

### 10.7 Alerts

**GET `/alerts`** — list (optionally `?unread=true`).
**WebSocket** `/ws` → subscribe to `/topic/tenant.{tenantId}.alerts` (subscription authorized against JWT tenant).

### 10.8 Reports

**GET `/reports/pnl?date=YYYY-MM-DD`** — P&L snapshot for tenant on a date.
**POST `/reports/pnl/run`** (ADMIN) — trigger on-demand P&L batch for a given date.

### 10.9 Account Summary (structured-concurrency composite read)

**GET `/summary`** — returns positions, recent breaches, and latest P&L for the tenant, fetched concurrently via a single `StructuredTaskScope` (see §12.3, FR-SUM-*).
```json
{
  "positions": [ { "instrumentSymbol": "CRUDE-OIL", "netPosition": 100, "positionLimit": 10000 } ],
  "recentBreaches": [ { "instrumentSymbol": "NAT-GAS", "netPosition": 12000, "detectedAt": "..." } ],
  "latestPnl": { "snapshotDate": "2026-06-26", "realizedPnl": 1520.50, "unrealizedPnl": -340.00 }
}
```
On any sub-read failure: `500` with a structured error (the scope cancels the remaining sub-reads fail-fast).

### 10.10 Pagination contract

All list endpoints return:
```json
{
  "content": [ ... ],
  "page": 0,
  "size": 20,
  "totalElements": 135,
  "totalPages": 7
}
```

---

## 11. Multi-Tenancy & Security

### 11.1 Tenant context propagation

- A `TenantContextFilter` runs after authentication, extracts `tenantId` (and `userId`, `traceId`) from the validated JWT, and **binds them as `ScopedValue`s** for the duration of request handling using `ScopedValue.where(TENANT_ID, id).run(...)` (or `callWhere`).
- All repository queries are tenant-scoped: either via an automatic Hibernate filter (`@FilterDef`/`@Filter` enabling `tenant_id = :tenantId`) or by always including `tenantId` in query methods, reading from the bound scoped value.
- Because the value is a `ScopedValue`, its binding is automatically and immutably available for the dynamic extent of the `run`/`callWhere` call and **is inherited by any subtask forked inside a structured-concurrency scope** during the request. No manual cleanup is needed — the binding ends when the `run` block exits.

> **Design decision DD-09 (ScopedValue over ThreadLocal):** Tenant/request context uses `ScopedValue` (final in Java 25, JEP 506) rather than `ThreadLocal`. Two reasons:
> 1. **Correctness with structured concurrency** — `ThreadLocal` values are **not** inherited by the new virtual threads that `StructuredTaskScope.fork()` creates, and `InheritableThreadLocal` is discouraged with virtual threads. `ScopedValue` bindings *are* inherited by forked subtasks, so `tenantId` flows correctly into the account-summary, bulk-position, and P&L fan-out subtasks (FR-SUM-04, NFR-CONC-04).
> 2. **Safety** — scoped values are immutable and bounded to a clear dynamic extent, eliminating the "forgot to clear the ThreadLocal on a pooled thread" leakage class entirely.
>
> **Note on async/Kafka paths:** Kafka consumer threads do *not* sit inside the request's scoped-value binding. In those paths the `tenantId` is read from the **event payload** (every event carries `tenantId`, see §9.2) and re-bound with `ScopedValue.where(TENANT_ID, event.tenantId()).run(...)` around the processing logic so downstream code and any forked subtasks see it consistently.

> **Design decision DD-07:** Discriminator-column multi-tenancy (shared schema, `tenant_id` column) is used rather than schema-per-tenant or DB-per-tenant. Rationale: simplest to operate for a portfolio project; isolation enforced at the query layer and verified by tests. Document the trade-off (noisy-neighbor risk, blast radius) as interview talking points.

### 11.2 Authentication & authorization

- JWT signed with HS256 (secret from env) or RS256 (keypair). Claims: `sub` (userId), `tenantId`, `role`, `exp`.
- Spring Security filter chain validates the token and populates the security context.
- Method-level authorization via `@PreAuthorize("hasRole('RISK_MANAGER')")` etc.
- Role matrix:

| Endpoint group | TRADER | RISK_MANAGER | ADMIN |
|---|---|---|---|
| Submit trade | ✅ | ✅ | ✅ |
| Configure instrument/limit | ❌ | ✅ | ✅ |
| View breaches | ❌ | ✅ | ✅ |
| Run P&L batch | ❌ | ❌ | ✅ |
| Onboard tenant | platform-admin only |

### 11.3 Security requirements checklist

- Passwords hashed with BCrypt (cost ≥ 10).
- No secrets committed; use `.env` + Docker secrets / environment variables.
- JWT expiry enforced; clock-skew tolerance ≤ 30s.
- Cross-tenant access returns `404`, never leaking existence.
- Input validation on every endpoint (Bean Validation `@Valid`).
- Rate limiting (FR-RATE-*) protects against abuse.

---

## 12. Concurrency Design

This project deliberately uses **Project Loom** as its concurrency foundation: **virtual threads** for cheap one-thread-per-task I/O, **structured concurrency** for fan-out/fan-in with fail-fast semantics, and **scoped values** for context propagation. The guiding principle: write straight-line blocking code, let virtual threads make it scalable, and let structured scopes make concurrent fan-out correct and leak-free.

### 12.1 Where concurrency lives

| Area | Mechanism | Requirement |
|---|---|---|
| HTTP request handling | **Virtual threads** (one per request, `spring.threads.virtual.enabled=true`) | NFR-CONC-01, NFR-PERF-01 |
| Position updates under concurrent trades | Redis atomic ops / Lua script (single-threaded Redis guarantees atomicity) | FR-POS-03 |
| Parallel risk evaluation across tenants | Kafka partitions + per-partition processing | NFR-PERF-03 |
| Outbox relay & other background pollers | Virtual-thread-per-task executor | NFR-CONC-02 |
| Account-summary composite read | **Structured concurrency** (`StructuredTaskScope`, fan-out 3 sub-reads) | FR-SUM-02, NFR-CONC-03 |
| Bulk position read (`GET /positions`) | **Structured concurrency** OR Redis pipeline (see §12.6) | FR-POS-04 |
| Parallel computation within P&L batch | **Structured concurrency** fan-out across tenants/instruments | FR-PNL-01, NFR-CONC-03 |
| Context propagation into subtasks | **Scoped values** (inherited by forked subtasks) | NFR-CONC-04 |

### 12.2 Virtual threads — usage rules

- **Request threads:** enable `spring.threads.virtual.enabled=true` so the servlet container dispatches each request on a virtual thread. This makes the blocking JDBC/Redis/Kafka calls in handlers scale without a sized platform-thread pool.
- **Executors:** wherever the app needs its own executor (outbox relay, scheduled pollers, ad-hoc fan-out), use `Executors.newVirtualThreadPerTaskExecutor()`. **Never** create a fixed-size platform-thread pool for I/O-bound work (NFR-CONC-02).
- **Never pool virtual threads.** They are cheap and disposable — one per task, then discarded. Pooling them defeats the model and can reintroduce the leakage/cleanup problems Loom removes.
- **Avoid pinning** (NFR-CONC-05): do not hold a `synchronized` monitor across a blocking call. Use `java.util.concurrent.locks.ReentrantLock` if a lock is genuinely needed. (JDK 25 / JEP 491 already removes most `synchronized` pinning, but the rule keeps the code safe and portable.) Verify with `-Djdk.tracePinnedThreads=full` during testing.

### 12.3 Structured concurrency — usage pattern

Use a `StructuredTaskScope` (preview, JEP 505) wherever a unit of work fans out into independent subtasks that must all complete, with fail-fast cancellation if any fails. The canonical shape (Java 25 API):

```java
// Pseudocode — Java 25, --enable-preview
try (var scope = StructuredTaskScope.open()) {          // default joiner: fail-fast
    Subtask<List<Position>> positions = scope.fork(() -> positionService.allFor(tenantId()));
    Subtask<List<Breach>>   breaches  = scope.fork(() -> riskService.recentBreaches(tenantId()));
    Subtask<PnlSnapshot>    pnl       = scope.fork(() -> reportService.latestPnl(tenantId()));

    scope.join();   // waits for all; propagates first failure, cancels the rest

    return new AccountSummary(positions.get(), breaches.get(), pnl.get());
}
```

Key properties this gives us (all defensible interview points):
- **No thread leaks** — the scope cannot exit the try-with-resources block until every forked subtask has finished or been cancelled (`StructureViolationException` enforces correct nesting).
- **Fail-fast** — the default joiner cancels sibling subtasks the moment one fails, so a slow/broken sub-read doesn't make the whole request hang (FR-SUM-03).
- **Context inheritance** — `tenantId()` reads the `ScopedValue` bound on the request thread; each forked subtask inherits that binding automatically (NFR-CONC-04).
- **Observability** — virtual-thread thread dumps (`jcmd <pid> Thread.dump_to_file -format=json`) render the scope as a parent/child tree, so a stuck summary call is debuggable.

> **Design decision DD-10 (structured concurrency for fan-out reads):** Composite reads (account summary, bulk positions, P&L compute) use `StructuredTaskScope` rather than raw `CompletableFuture`/`ExecutorService`. Rationale: structured scopes guarantee subtask lifetime confinement, fail-fast cancellation, and scoped-value inheritance — exactly the correctness properties these multi-source reads need. `CompletableFuture` remains acceptable for genuinely independent fire-and-forget composition, but is **not** used where a parent must own subtask lifetimes.

> **Scope of use — do NOT over-apply.** Structured concurrency is for parent-owns-children fan-out. It is **not** used for: the Kafka consumption loop (that is a long-lived, partition-ordered stream, not a bounded fan-out), single-dependency sequential logic, or the per-trade risk update (which is intentionally sequential per partition for ordering). Reaching for `StructuredTaskScope` everywhere is an anti-pattern and would be called out in review.

### 12.4 Ordering guarantees

- Kafka topics keyed by `tenantId` → per-tenant ordering preserved.
- Within a tenant, an instrument's trades are processed in submission order, so position math is correct.
- Consumer concurrency = partition count; one partition is consumed by one thread at a time. (Per-partition single-threaded consumption is preserved even though processing inside may use virtual threads — we do **not** fan out the position update itself, to keep ordering.)

### 12.5 Avoiding lost updates

- **Never** read position into JVM, modify, then write back without atomicity.
- Use Redis atomic primitives or the Lua script (DD-06). Atomicity here comes from Redis being single-threaded for command execution, **not** from JVM locking — so virtual threads introduce no new race on position state.
- The DB position rebuild job (FR-POS-05) recomputes positions by summing signed trade quantities grouped by `(tenant, instrument)` — a deterministic source of truth.

### 12.6 Bulk position read — two valid implementations

`GET /positions` reads N instruments' positions. Either:
- **(a) Redis pipeline / `MGET`** — one round trip, simplest and fastest; preferred for pure Redis reads; or
- **(b) Structured concurrency** — fork one subtask per instrument when each read involves more than a single Redis call (e.g. position + limit + utilization from mixed sources).

Choose (a) when it is a pure multi-key Redis read; choose (b) when sub-reads hit different stores. Document the choice in code comments referencing this section.

### 12.7 Required concurrency tests

- **CT-01:** N threads submit trades on one instrument; assert exact final position (FR-POS-03 AC).
- **CT-02:** Redelivered Kafka event does not double-apply (idempotency, §9.3 note).
- **CT-03:** Two tenants under concurrent load do not affect each other's positions or rate limits.
- **CT-04 (structured concurrency fail-fast):** In the account-summary scope, force one subtask to throw; assert the sibling subtasks are cancelled (state `UNAVAILABLE`/interrupted), no thread leaks, and the endpoint returns a single `500`.
- **CT-05 (scoped-value inheritance):** Two tenants call `GET /summary` concurrently; assert each tenant's subtasks read only their own `tenantId` from the inherited scoped value (no cross-contamination).
- **CT-06 (pinning):** Run the load test with `-Djdk.tracePinnedThreads=full`; assert no pinning events on the hot paths.

---

## 13. Risk Engine Logic

### 13.1 Processing algorithm (per `trade.submitted` event)

```
1. Parse event → (tenantId, tradeId, symbol, side, quantity, price)
2. If alreadyApplied(tenantId, tradeId): ACK and return        # idempotency
3. delta = (side == BUY) ? +quantity : -quantity
4. limit = limitOf(tenantId, symbol)                            # cache or DB
5. result = LUA updatePositionAndEvaluate(posKey, delta, limit)
       → returns { newPosition, breached }
6. Publish PositionUpdatedEvent(newPosition, tradeId)
7. If result.breached AND not breachAlreadyRecorded(tradeId):
       - persist RiskBreach (unique on tenant+tradeId)
       - publish RiskBreachedEvent
8. markApplied(tenantId, tradeId)                               # TTL set
9. Commit Kafka offset
```

### 13.2 BLOCK mode (FR-RISK-04, Should)

In BLOCK mode the ingestion path (gateway/trade-ingestion) performs a **pre-check** before persisting:
```
projected = currentPosition(symbol) + delta
if abs(projected) > limit: reject trade with 409 BREACH_BLOCKED
```
This pre-check reads position from Redis. Note the inherent race between pre-check and persist under high concurrency; document that BLOCK mode trades some throughput for synchronous safety, and that the authoritative async check still runs.

### 13.3 Edge cases to handle

| Case | Expected behavior |
|---|---|
| Limit reduced below current position | Next trade (or a re-evaluation) registers a breach even if it reduces exposure? → Only evaluate on net change; a trade that *reduces* abs(position) below limit clears nothing but does not breach. Define: breach fires only when `abs(newPosition) > limit`. |
| Trade that flips long→short crossing zero | Position math is purely additive; breach evaluated on resulting abs value. |
| Mark price missing for unrealized P&L | Unrealized P&L = 0 and snapshot flagged `markPriceMissing=true`. |
| Duplicate trade event redelivery | Skipped via applied-set (§9.3). |

---

## 14. P&L & Reporting

### 14.1 Batch job structure (Spring Batch)

```
Job: pnlSnapshotJob(parameter: snapshotDate)
  Step 1: prepare        — resolve tenants & instruments in scope
  Step 2: compute        — partitioned by tenant; per partition:
            reader  → trades for tenant up to snapshotDate
            processor → fold trades into per-instrument {qty, avgCost, realizedPnl}
            writer  → upsert pnl_snapshots (idempotent on tenant+date+symbol)
  Step 3: finalize       — mark job complete, emit summary
```

**In-step parallelism via structured concurrency.** Within a tenant partition, the per-instrument P&L computations are independent and are fanned out using a `StructuredTaskScope` (NFR-CONC-03): one subtask per instrument, forked on a virtual thread, joined fail-fast so any computation error aborts that partition cleanly without leaking threads. The `tenantId` scoped value is bound around the partition and inherited by each instrument subtask. This replaces a hand-rolled `ExecutorService`/`CompletableFuture` fan-out.

```java
// Pseudocode — per-tenant partition, Java 25, --enable-preview
ScopedValue.where(TENANT_ID, tenantId).run(() -> {
    try (var scope = StructuredTaskScope.<InstrumentPnl>open()) {
        for (String symbol : instrumentsForTenant) {
            scope.fork(() -> computeInstrumentPnl(symbol, snapshotDate));
        }
        scope.join();                 // fail-fast; cancels siblings on first error
        // collect results (via a collecting Joiner or by holding Subtask handles) and upsert
    }
});
```

> Note: Spring Batch's own partitioning still provides restartability and chunk semantics at the tenant level; structured concurrency is used *inside* a partition for the instrument fan-out. Keep the two layers distinct.

### 14.2 Realized P&L (WAC) algorithm — per instrument, chronological

```
state: position = 0, avgCost = 0, realized = 0
for each trade (in submitted_at order):
    signedQty = (BUY ? +q : -q)
    if position and signedQty have the SAME sign (increasing exposure):
        avgCost = (avgCost*|position| + price*q) / (|position| + q)
        position += signedQty
    else:  # reducing / closing exposure
        closingQty = min(q, |position|)
        realized += closingQty * (price - avgCost) * sign(position)
        position += signedQty
        if position crosses zero (flips):
            avgCost = price   # remaining qty opens new position at trade price
        if position == 0:
            avgCost = 0
unrealized = position * (markPrice - avgCost)   # 0 if markPrice missing
```

> Validate this algorithm with explicit unit tests covering: pure long, pure short, partial close, full close, position flip, and zero-crossing. (This mirrors the kind of assertion-heavy scenario testing you've done before — aim for a fixed set of scenarios with expected realized/unrealized values.)

### 14.3 Idempotency (FR-PNL-05)

Re-running for the same `snapshotDate` performs an UPSERT on `(tenant, date, symbol)` — no duplicate rows.

---

## 15. Error Handling & Status Codes

### 15.1 Standard error body

```json
{
  "timestamp": "2026-06-27T10:15:30.123Z",
  "status": 400,
  "errorCode": "INVALID_QUANTITY",
  "message": "quantity must be a positive integer",
  "path": "/api/v1/trades",
  "traceId": "abc123"
}
```

### 15.2 Status code conventions

| Code | When |
|---|---|
| 200 | Successful read |
| 201 | Resource created (trade, instrument, tenant) |
| 400 | Validation failure |
| 401 | Missing/invalid JWT |
| 403 | Authenticated but unauthorized role / inactive tenant |
| 404 | Resource not found OR belongs to another tenant |
| 409 | Conflict (BLOCK-mode breach, idempotency conflict) |
| 429 | Rate limit exceeded (include `Retry-After`) |
| 500 | Unexpected server error |

### 15.3 Error code catalog (extensible)

`INVALID_QUANTITY`, `INVALID_PRICE`, `INVALID_SIDE`, `UNKNOWN_INSTRUMENT`, `QUANTITY_TOO_LARGE`, `BREACH_BLOCKED`, `RATE_LIMITED`, `UNAUTHORIZED`, `FORBIDDEN_ROLE`, `TENANT_INACTIVE`, `NOT_FOUND`, `DUPLICATE_IDEMPOTENCY_KEY`, `INTERNAL_ERROR`.

---

## 16. Observability

| ID | Requirement |
|---|---|
| OBS-01 | Structured (JSON) logs with MDC fields `tenantId`, `userId`, `tradeId`, `traceId`. |
| OBS-02 | Spring Boot Actuator: `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`. |
| OBS-03 | Micrometer counters: trades ingested, breaches detected, alerts delivered, rate-limit rejections — tagged by tenant (bounded cardinality). |
| OBS-04 | Kafka consumer lag visible (via metrics or a simple admin endpoint). |
| OBS-05 | Each Kafka event carries `eventId` and `occurredAt` for traceability. |

---

## 17. Testing Strategy

### 17.1 Test pyramid

| Layer | Tooling | Coverage focus |
|---|---|---|
| Unit | JUnit 5 + Mockito | P&L algorithm, validation rules, position math, rate-limit logic |
| Slice | `@WebMvcTest`, `@DataJpaTest` | controllers, repositories |
| Integration | Testcontainers (Postgres, Redis, Kafka) | end-to-end event flow, tenant isolation, concurrency |
| Concurrency | JUnit + `CountDownLatch` / virtual-thread executor + Awaitility | FR-POS-03, idempotency |
| Loom | JUnit + `StructuredTaskScope` + `-Djdk.tracePinnedThreads=full` | fail-fast cancellation, scoped-value inheritance, no pinning |

> Tests that exercise structured concurrency must run on Java 25 with `--enable-preview` (configure Surefire/Failsafe `argLine` accordingly).

### 17.2 Mandatory test cases (traceable to requirements)

| Test ID | Verifies | Requirement |
|---|---|---|
| T-ISO-01 | Tenant A cannot read tenant B's trades | FR-TEN-02 |
| T-ISO-02 | Cross-tenant resource access returns 404 | FR-TEN-02 |
| T-VAL-01..05 | Each validation rule rejects bad input | FR-TRADE-02 |
| T-POS-01 | 100 concurrent buys → exact net position | FR-POS-03 |
| T-IDEM-01 | Redelivered trade event applied once | NFR-REL-02 |
| T-RISK-01 | Breach published when limit exceeded | FR-RISK-02 |
| T-RISK-02 | No duplicate breach on event replay | FR-RISK-03 |
| T-ALERT-01 | Alert delivered only to owning tenant's subscriber | FR-ALERT-03 |
| T-RATE-01 | 101st request in window → 429 | FR-RATE-02 |
| T-PNL-01..06 | P&L scenarios (long/short/close/flip/zero/missing-mark) | FR-PNL-02 |
| T-PNL-IDEM | Re-running batch for same date → no duplicates | FR-PNL-05 |
| T-SC-01 | Account-summary scope fails fast: one failing subtask cancels siblings, no leak (CT-04) | FR-SUM-03 |
| T-SC-02 | Scoped tenant value inherited by summary subtasks; no cross-tenant bleed under concurrency (CT-05) | FR-SUM-04, NFR-CONC-04 |
| T-SC-03 | No virtual-thread pinning on hot paths (CT-06) | NFR-CONC-05 |
| T-VT-01 | Request handling runs on a virtual thread (assert `Thread.currentThread().isVirtual()` in a test endpoint) | NFR-CONC-01 |

### 17.3 Coverage gate

- CI fails if line coverage on `risk-engine` and position modules < 80% (NFR-TEST-01).
- Integration tests run on every PR via Testcontainers.

---

## 18. Deployment & Infrastructure

### 18.1 Local environment (`docker compose up`)

Services in `docker-compose.yml`:
- `postgres` (with init via Flyway on app start)
- `redis`
- `zookeeper` + `kafka` (or KRaft-mode single-broker Kafka)
- `tradeflow-app` (the Spring Boot application)
- (optional) `kafka-ui` and `redis-commander` for debugging

### 18.2 Configuration

- All config via environment variables / `application.yml` profiles (`local`, `docker`, `test`).
- Secrets (JWT key, DB password) injected via env, never committed.
- Flyway runs migrations on startup.
- **Virtual threads:** set `spring.threads.virtual.enabled=true` (NFR-CONC-01).
- **Java toolchain:** build with JDK 25; Maven compiler `<release>25</release>` and `--enable-preview` (compiler + Surefire/Failsafe `argLine` + runtime). Base Docker image: a JDK 25 runtime.

### 18.3 Build & run

```
# build (preview features enabled via pom compiler/argLine config)
mvn clean verify

# run full stack
docker compose up --build

# run the app standalone with preview enabled
java --enable-preview -jar target/tradeflow.jar

# Swagger UI
http://localhost:8080/swagger-ui.html

# diagnose virtual-thread pinning while load testing
java --enable-preview -Djdk.tracePinnedThreads=full -jar target/tradeflow.jar
```

> **Reminder:** `--enable-preview` is required only because of **structured concurrency** (preview in Java 25). Virtual threads and scoped values are final. When structured concurrency is finalized (expected ~Java 27), the flag can be dropped.

### 18.4 CI (GitHub Actions, recommended)

- `build` job runs on **JDK 25** with preview enabled; `mvn verify` with Testcontainers.
- Coverage report (JaCoCo) published; gate at thresholds.
- Optional: build & push Docker image (JDK 25 base).

---

## 19. Implementation Roadmap

> Each phase ends with a demoable increment and its acceptance tests passing.

### Phase 0 — Project skeleton (2–3 days)
- Maven multi-module (or modular package) structure on **JDK 25** (compiler `release=25`, `--enable-preview`), Spring Boot app, Flyway baseline, Actuator, Swagger, Docker Compose with Postgres/Redis/Kafka. Enable `spring.threads.virtual.enabled=true`.
- **Done when:** `docker compose up` runs; `/actuator/health` is UP; T-VT-01 (request on a virtual thread) passes.

### Phase 1 — Auth, tenancy, instruments (1.5 weeks)
- FR-TEN-01..04, FR-INST-01..03, security filter chain, **`ScopedValue`-based `TenantContext`** (DD-09).
- **Done when:** T-ISO-01, T-ISO-02 pass; login issues JWT; instruments CRUD works.

### Phase 2 — Trade ingestion + outbox + Kafka (1.5 weeks)
- FR-TRADE-01..06, outbox relay (virtual-thread executor), `trade.submitted` publishing.
- **Done when:** trade persists, event observed on topic, T-VAL-01..05 pass.

### Phase 3 — Risk engine + positions (2 weeks)
- FR-POS-*, FR-RISK-01..03, Lua script, idempotency. Bulk `GET /positions` per §12.6.
- **Done when:** T-POS-01, T-IDEM-01, T-RISK-01, T-RISK-02 pass; `GET /positions` works.

### Phase 4 — Alerts + rate limiting (1 week)
- FR-ALERT-01..04, FR-RATE-01..04, WebSocket.
- **Done when:** T-ALERT-01, T-RATE-01 pass; breach pushes a live alert.

### Phase 5 — P&L batch + reporting + account summary (1.5 weeks)
- FR-PNL-01..05, Spring Batch job with **structured-concurrency** instrument fan-out (§14.1), report APIs, and the `GET /summary` composite read (FR-SUM-*).
- **Done when:** T-PNL-01..06, T-PNL-IDEM, T-SC-01, T-SC-02 pass; report and summary endpoints return data.

### Phase 6 — Polish & docs (1 week)
- README with architecture diagram, design-decision log (DD-01..10), coverage badge, demo script/seed data, optional minimal frontend. Add a short "Concurrency model" section to the README explaining the virtual-thread / structured-concurrency / scoped-value choices.
- **Done when:** repo is clone-and-run; coverage gate green; OpenAPI complete; T-SC-03 (no pinning) passes.

**Total: ~8–9 weeks at a steady solo pace.**

---

## 20. Open Questions & Future Work

### 20.1 Open questions (decide before/while building)
- **OQ-1:** JWT signing — HS256 (simple) vs RS256 (more realistic)? *Default: HS256 for v1.*
- **OQ-2:** Cost basis — WAC (chosen) vs FIFO. FIFO deferred.
- **OQ-3:** Real-time transport — WebSocket/STOMP vs SSE. *Default: WebSocket/STOMP.*
- **OQ-4:** Single Kafka broker (KRaft) vs Zookeeper+broker for local. *Default: single-broker KRaft to reduce containers.*
- **OQ-5:** Accept the **preview** dependency on structured concurrency (requires `--enable-preview`, API may change before finalization ~Java 27)? *Default: yes for v1; the API surface used (`open()`, `fork()`, `join()`, a collecting/fail-fast `Joiner`) is small and easy to migrate. Isolate it behind a thin internal helper (e.g. `Scopes.fanOut(...)`) so a future API change touches one class.*

### 20.2 Future work (explicitly post-v1)
- Split modules into independent microservices deployed separately.
- FIFO lot tracking and configurable cost-basis method.
- Real/mock market data feed driving live mark prices and continuous unrealized P&L.
- Options & non-linear instruments with pricing models.
- Kubernetes deployment + horizontal scaling, Redis Cluster, partitioned multi-broker Kafka.
- Per-tenant resource quotas and noisy-neighbor protection.
- Audit log immutability / event sourcing for the full trade lifecycle.
- Migrate structured-concurrency code off `--enable-preview` once the API is finalized (~Java 27).

---

## Appendix A — Requirement ID Index (quick reference)

- **Tenancy:** FR-TEN-01..05
- **Instruments:** FR-INST-01..05
- **Trades:** FR-TRADE-01..07
- **Positions:** FR-POS-01..05
- **Risk:** FR-RISK-01..05
- **Alerts:** FR-ALERT-01..05
- **Rate limiting:** FR-RATE-01..04
- **P&L:** FR-PNL-01..06
- **Account summary:** FR-SUM-01..04
- **Non-functional:** NFR-PERF / REL / SEC / DATA / CONC / TEST / OBS / PORT / DOC
- **Design decisions:** DD-01..10 (DD-08 Java 25 + Loom; DD-09 ScopedValue over ThreadLocal; DD-10 structured concurrency for fan-out reads)

---

*End of specification v1.1. Update this document before changing behavior; keep code traceable to requirement IDs.*
