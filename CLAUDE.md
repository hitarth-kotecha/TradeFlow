# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is
TradeFlow — a multi-tenant trading risk engine, built as a **modular monolith** on **Java 25 + Spring Boot 3.5**. It is a spec-driven, learning-focused portfolio project. The single source of truth is [TradeFlow_SPEC.md](TradeFlow_SPEC.md) (v1.1): every change maps to a requirement ID (e.g. `FR-TRADE-03`). Update the spec **before** changing behavior.

## Toolchain (hard requirements)
- **JDK 25**, **Maven 3.9.x**, **Docker + Docker Compose**.
- Structured concurrency (`StructuredTaskScope`, JEP 505) is a **preview** API, so `--enable-preview` is required at **compile and runtime** (configured in the parent POM compiler + surefire). Virtual threads and `ScopedValue` are final (no flag).

## Common commands
- Build all + tests: `mvn clean verify` (or `mvn install`)
- Build, skip tests: `mvn install -DskipTests`
- One module's tests: `mvn -pl gateway test`
- Single test class: `mvn -pl gateway test -Dtest=VirtualThreadTest`
- Run the app standalone: `java --enable-preview -jar gateway/target/gateway-0.1.0-SNAPSHOT.jar`
- Full local stack: `mvn clean package && docker compose up --build`
- Stack + debug UIs (kafka-ui :8081, redis-commander :8082): `docker compose --profile tools up`
- Health `http://localhost:8080/actuator/health` · Swagger `http://localhost:8080/swagger-ui.html`

## Module map (dependencies flow toward `common`)
- **common** — shared DTOs, event schemas (§9.2), `ScopedValue` tenant context (DD-09), error body (§15). Depends on nothing; everything depends on it.
- **gateway** — THE runnable app and single deployable. Entrypoint `com.tradeflow.TradeFlowApplication` lives in the **root package** so component scan covers every module. Holds security/JWT, controllers, rate limiter, WebSocket, datasource + Flyway. **Only** module with `spring-boot-maven-plugin`.
- **trade-ingestion** — validate/persist trades, transactional outbox (DD-03), publish `trade.submitted`.
- **risk-engine** — consume `trade.submitted`, atomic Redis Lua position update + limit eval (DD-06), consumer idempotency (§9.3), publish `position.updated` / `risk.breached`. Coverage-gated ≥80% (NFR-TEST-01).
- **position-store** — Redis position state + Postgres rebuild job (FR-POS-05).
- **alert-service** — consume `risk.breached`, push WebSocket/STOMP per-tenant alerts.
- **reporting** — Spring Batch P&L (WAC, §14.2) with `StructuredTaskScope` instrument fan-out (§14.1); report + `/summary` APIs.

Communication that would cross a future service boundary goes through **Kafka, not direct calls** (DD-01).

## Architecture essentials (read before non-trivial changes)
- **Concurrency thesis (DD-08/09/10):** virtual threads for I/O; `StructuredTaskScope` for *bounded fan-out reads only* (account summary, P&L) — NOT the Kafka consume loop or the per-trade update; `ScopedValue` (never `ThreadLocal`) for tenant context so it's inherited by forked subtasks. No `synchronized` around blocking I/O (pinning) — use `ReentrantLock`. Isolate all `StructuredTaskScope` use behind one helper in `common` (preview-API churn).
- **Tenant isolation is the headline invariant:** every query scoped by `tenantId`; cross-tenant reads return **404, never 403**. Verified by tests.
- **Idempotency everywhere:** trade idempotency key (24h), applied-set for trade events, unique `(tenant, trade_id)` for breaches, UPSERT for P&L snapshots.
- **Schema is Flyway-owned** (`gateway/src/main/resources/db/migration`). **Never edit an applied migration — add a new `V{n}__*.sql`.**

## Config / profiles
- `application.yml` = base (host dev: Postgres at `localhost`). `application-docker.yml` overrides the host to the `postgres` service name. The app container runs with `SPRING_PROFILES_ACTIVE=docker`.

## Testing
- Pyramid per §17: unit (JUnit 5 + Mockito), slice (`@WebMvcTest`/`@DataJpaTest`), integration (**Testcontainers** Postgres/Redis/Kafka, from Phase 1), concurrency (`CountDownLatch`), Loom (fail-fast, scoped-value inheritance, `-Djdk.tracePinnedThreads=full`). Tests run with `--enable-preview`. Each requirement has a traceable test ID (e.g. T-VT-01).

## Working style
Learning-first: explain the fundamental (the *why* + trade-offs) before/while implementing, in small reviewable slices tied to requirement IDs. See the approved plan and the `~/.claude` project memory.
