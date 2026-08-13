# Architecture

This document is the deep dive behind [README.md](README.md). It covers what's actually built in
Phase 1 — the real-time trading system. The payment system (Phase 2) and deployment/observability
stack (Phase 3) are described only as scope, not design, since they don't exist yet.

## 1. System architecture overview

Five components communicate exclusively through Kafka, never by direct call. Each is
independently deployable and independently recoverable: killing any one of them loses no data —
the next consumer to catch up on its topic replays whatever it missed.

```
Order API → [orders] → Risk Service → [orders-validated] → Matching Engine → [trades] → Execution Service
```

`Price Feed Service` runs alongside this pipeline on its own schedule, publishing to `[prices]`
and caching in Redis; it doesn't sit in the order's critical path.

## 2. Trading system detail

### Problem
Orders must be accepted, risk-checked, matched, and settled without ever being lost or
double-counted, fast enough that a trader's fill reflects the market they saw when they clicked.

### Solution
See the [README's architecture diagram](README.md#architecture-diagram) for the full picture.
The core design decision is **who owns what state, and who is allowed to write it**:

- `OrderService` is the only writer of an order's *initial* state (`PENDING`).
- `RiskService` is the only writer of `VALIDATED` / risk-driven `REJECTED`.
- `MatchingEngineService` never touches Postgres — it's pure in-memory state (the order book)
  plus Kafka publish. This keeps the matching hot path off the database entirely.
- `ExecutionService` is the only writer of `FILLED` / `PARTIALLY_FILLED` and the only place a
  `Trade` row is created. One writer per status transition means no lost updates, no locking
  needed across services.

### Data flow (happy path)
1. `POST /v1/orders` → `OrderServiceImpl.submitOrder()` persists `Order{status=PENDING}`,
   publishes `OrderEvent` to `orders` (key = symbol, 10 partitions).
2. `OrderEventConsumer` batch-consumes `orders`, hands each event to `RiskServiceImpl.evaluate()`.
3. Risk checks (in order): open notional vs. `trading.risk.max-notional-per-client`, then order
   velocity vs. `trading.risk.max-orders-per-window` in `trading.risk.window-seconds` (in-memory
   sliding window via `OrderVelocityTracker` — single-instance only, see Tradeoffs).
   - Fails either → `Order.status=REJECTED`, `RiskAlert` persisted, `RiskAlertEvent` → `risk-alerts`.
   - Passes both → `Order.status=VALIDATED`, `OrderValidatedEvent` → `orders-validated`.
4. `MatchingEngineConsumerRunner` (a dedicated raw `KafkaConsumer`, not a Spring listener — see
   Pattern 3 below) polls `orders-validated` in batches, hands each event to
   `MatchingEngineServiceImpl.match()`.
5. `MatchingEngineServiceImpl` looks up (or creates) the symbol's `OrderBook`, matches at
   price/time priority, and for every fill publishes a `TradeEvent` to `trades` — carrying the
   **resulting status of both orders** so the next stage never has to recompute fill state.
6. `TradeEventConsumer` batch-consumes `trades` sequentially (not in parallel — a batch can
   contain multiple fills against the same order, and status updates must apply in order),
   hands each to `ExecutionServiceImpl.recordTrade()`.
7. `ExecutionServiceImpl` persists the `Trade`, updates both `Order` rows, appends to the
   Chronicle trade journal, and pushes the new status over `OrderStreamHandler`'s WebSocket.

### Tradeoffs
See the [Design tradeoffs table in README.md](README.md#design-tradeoffs) — repeated here isn't
useful; that table *is* the canonical list.

## 3. Payment system (Phase 2 — not yet built)

Ledger, Settlement (saga pattern), Fraud Detection, Notification (retry + DLQ), Reconciliation.
Scope only, per the original spec's System 2 section — no design decisions have been made yet
because no code exists yet.

## 4. Low-latency patterns

All six patterns from the spec, with what Phase 1 actually does:

**1. Virtual threads** — `spring.threads.virtual.enabled=true` for the Order API's request
handling; a dedicated `ExecutorService` (`VirtualThreadConfig.virtualThreadExecutor()`) for
WebSocket broadcast fan-out (`OrderStreamHandler`) and for the Matching Engine's poll loop itself.

**2. Lock-free-ish order book** — `ConcurrentHashMap<String, OrderBook>` for symbol lookup (truly
lock-free: different symbols never block each other). Matching *within* a symbol is
`synchronized` on that symbol's `OrderBook` instance — see
[OrderBook.java](src/main/java/com/dcbate/tradingplatform/trading/service/matching/OrderBook.java).
A genuinely wait-free single-symbol book (lock-free skip lists, CAS-based price levels) is a much
larger undertaking than Phase 1's scope justifies; per-symbol locking already gives full
cross-symbol parallelism, which is what horizontal throughput actually depends on.

**3. Batch processing** — `RiskService` and `ExecutionService` consume via Spring Kafka's batch
listener (`containerFactory=batchListenerFactory`, `AckMode.BATCH`). The Matching Engine goes
further: it bypasses Spring Kafka entirely and drives its own `KafkaConsumer.poll()` loop
(`MatchingEngineConsumerRunner`) with `max.poll.records` from `trading.matching-engine.batch-size`
— the spec's literal `consumer.poll(Duration.ofMillis(100))` pattern, with no listener-container
overhead between poll and match.

**4. Thread affinity** — implemented via `net.openhft:affinity`
(`MatchingEngineConsumerRunner.acquireAffinityLockIfEnabled()`), **disabled by default**
(`trading.matching-engine.thread-affinity.enabled=false`). It needs a native JNI library that
isn't guaranteed present on every dev machine or container base image; enabling it is a one-line
config change plus a benchmark to confirm it actually helps on the target hardware.

**5. Non-blocking WebSocket fan-out** — `OrderStreamHandler` holds a `ConcurrentHashMap`-backed
session set and dispatches each send onto the virtual-thread executor, so one slow or dead
session can never block another subscriber or the publishing thread. This uses Spring MVC's
`TextWebSocketHandler`, not WebFlux's reactive `Flux`/`Sinks` — chosen to keep one stack (Spring
MVC + virtual threads) instead of mixing reactive and blocking programming models for a single
broadcast use case. The effect (thread-per-connection cost eliminated) is the same; the
mechanism differs from the spec's `Flux` sketch.

**6. Off-heap trade journal** — Chronicle Queue, memory-mapped file, zero GC pressure. See
[ChronicleTradeJournalWriter](src/main/java/com/dcbate/tradingplatform/chronicle/ChronicleTradeJournalWriter.java).
Requires JVM `--add-opens`/`--add-exports` flags on JDK 17+ to reach internal APIs — wired into
`pom.xml` (`chronicle.jvm.opens`, applied to Surefire, `spring-boot:run`, and `docker/Dockerfile`).

## 5. Monitoring & observability (Phase 1 slice)

Structured logging (`@Slf4j` everywhere), Spring Boot Actuator + Micrometer/Prometheus
(`/actuator/prometheus`), a custom `MatchingEngineHealthIndicator` exposing live order-book depth,
and a `price.anomalies` counter tagged by symbol. Jaeger tracing and Grafana dashboards are Phase 3.

## 6. Testing strategy

JUnit 5 + Mockito unit tests per service (dependencies mocked, one class per test file), plus a
dedicated `OrderBookTest` exercising the matching algorithm directly with no framework involved.
One Testcontainers `@SpringBootTest` (`OrderFlowIntegrationTest`) proves the entire pipeline
against real Kafka, PostgreSQL, and Redis containers — not mocks. See
[README's coverage section](README.md#test-coverage--stated-honestly) for what's *not* covered
and why.

## 7. Security & compliance (Phase 1 slice)

JWT resource-server auth (HS256), `@PreAuthorize` role checks (`TRADER`/`ADMIN`/`AUDITOR`/
`COMPLIANCE_OFFICER`), no secrets committed (`JWT_SECRET`/`GEMINI_API_KEY` via env vars with
local-only placeholders). GCP Secret Manager/KMS integration, full OAuth2 identity provider, and
PCI/SOX audit tooling are Phase 3 — this repo's security posture is "correct primitives, not yet
production-hardened."

## 8. Deployment & operations (Phase 1 slice)

Multi-stage `docker/Dockerfile` (Alpine, non-root user, healthcheck), `docker/docker-compose.yml`
for local Postgres/Redis/Kafka/app, and a `.github/workflows/ci.yml` that runs `mvn verify` and
builds the Docker image on every push. Kubernetes manifests, Helm charts, GCP deployment, and a
production CI/CD promotion pipeline are Phase 3.

## 9. Future improvements

- In-memory (or Redis-backed) fallback queue for order submission when Kafka is down, per the
  original spec's error-handling table — not implemented in Phase 1; today a Kafka outage fails
  order submission outright rather than degrading gracefully.
- Distribute `OrderVelocityTracker` via Redis so risk velocity limits hold under multiple
  Risk Service instances (today it's correct only for a single instance).
- Benchmark thread affinity and Chronicle Queue against the spec's latency targets — targets
  are design intent until Gatling load tests exist (Phase 3).
