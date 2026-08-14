# Architecture

This document is the deep dive behind [README.md](README.md). It covers what's actually built: the
retail banking core (accounts, deposits/withdrawals, internal transfers, FX conversion, loans —
see §3a and [docs/ACCOUNTS.md](docs/ACCOUNTS.md)), the FX trading desk (Phase 1, §2), and the
cross-bank payment & notification system (Phase 2, §3). Kubernetes manifests and a Helm chart
exist and are verified against a local `kind` cluster (§9); GCP deployment, Jaeger tracing,
Grafana dashboards, and Gatling load tests remain scope, described in §10 since they don't exist
yet.

## 1. System architecture overview

Both systems are event-driven end to end: every component communicates exclusively through
Kafka, never by direct call. Each is independently deployable and independently recoverable —
killing any one of them loses no data, since the next consumer to catch up on its topic replays
whatever it missed.

```
Order API → [orders] → Risk Service → [orders-validated] → Matching Engine → [trades] → Execution Service

Payment API → [payments] → Fraud Detection → [payments-validated] → Settlement (saga) → [notifications] → Notification Service
```

`Price Feed Service` and `Reconciliation Scheduler` run alongside these pipelines on their own
schedules; neither sits in a request's critical path.

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
   publishes `OrderEvent` to `orders` (key = currency pair, 10 partitions).
2. `OrderEventConsumer` batch-consumes `orders`, hands each event to `RiskServiceImpl.evaluate()`.
3. Risk checks (in order): open notional vs. `trading.risk.max-notional-per-client`, then order
   velocity vs. `trading.risk.max-orders-per-window` in `trading.risk.window-seconds` (in-memory
   sliding window via `OrderVelocityTracker` — single-instance only, see Tradeoffs).
   - Fails either → `Order.status=REJECTED`, `RiskAlert` persisted, `RiskAlertEvent` → `risk-alerts`.
   - Passes both → `Order.status=VALIDATED`, `OrderValidatedEvent` → `orders-validated`.
4. `MatchingEngineConsumerRunner` (a dedicated raw `KafkaConsumer`, not a Spring listener — see
   Pattern 3 below) polls `orders-validated` in batches, hands each event to
   `MatchingEngineServiceImpl.match()`.
5. `MatchingEngineServiceImpl` looks up (or creates) the currency pair's `OrderBook`, matches at
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

## 3. Payment system detail

### Problem
Payments can't be charged twice even with a retried request, can't disappear even if a service
crashes mid-flight, and every failure needs a customer-visible reason — while fraud has to be
caught without a real card/merchant/bank-tokenization system to check against.

### Solution
See [docs/PAYMENT_SYSTEM.md](docs/PAYMENT_SYSTEM.md) for the full lifecycle, saga walkthrough,
and fraud/failure scenarios. The core design decisions:

- `PaymentService` is idempotent on `idempotencyKey` (DB unique constraint) — the only writer of
  a payment's initial `PENDING` state.
- `FraudDetectionService` is the only writer of `BLOCKED`/`UNDER_REVIEW` — both terminal in Phase
  2 (no compliance-officer approval endpoint yet).
- `SettlementService` is a saga **orchestrator**, not choreographed via extra Kafka hops: reserve,
  ledger, and bank-clear are direct calls inside one transaction, so the compensation logic on
  failure lives in one reviewable place instead of being spread across consumers.
- `LedgerService` is append-only. Compensation writes *reversing* rows; it never edits or deletes
  a booked entry — the ledger's history is the audit trail.
- `NotificationService` only ever persists a `Notification` row on success or as a terminal
  dead-letter record — a failed attempt in between leaves no row, so Kafka's retry mechanism
  never sees stale state to reconcile.

### Data flow (happy path)
1. `POST /v1/payments` → `PaymentServiceImpl.submitPayment()`: idempotency check, then persists
   `Payment{status=PENDING}`, publishes `PaymentEvent` → `payments` (key=clientId, 20 partitions).
2. `PaymentEventConsumer` → `FraudDetectionServiceImpl.evaluate()`: velocity, country-change, and
   amount-vs-average rules (first match wins). Flagged → `BLOCKED`/`UNDER_REVIEW`, `FraudFlag`
   persisted, `FraudAlertEvent` → `fraud-alerts`, a `NotificationEvent` → `notifications`. Clean →
   `PaymentValidatedEvent` → `payments-validated`.
3. `SettlementEventConsumer` → `SettlementServiceImpl.process()`: reserve (`Payment.status =
   RESERVED`), `LedgerService.recordDoubleEntry()` (one DEBIT + one CREDIT, archived to
   `ledger-entries`), then `BankClearingClient.clear()`. Success → `SETTLED`. Failure →
   `LedgerService.reverseEntries()`, `Settlement.status = COMPENSATED`, `Payment.status = FAILED`.
   Either way, a `NotificationEvent` → `notifications`.
4. `NotificationEventConsumer` (`@RetryableTopic`) → `NotificationServiceImpl.deliver()`: Claude
   summarization for non-success outcomes, delivery via the Email/Slack stand-ins, `Notification`
   persisted only on success. Exhausted retries hit `@DltHandler` → `markDeadLettered()`.
5. `ReconciliationScheduler` (cron, default 02:00 daily) → `ReconciliationServiceImpl.reconcile()`:
   every `CLEARED` settlement's ledger entries must net to zero; real discrepancies persist a
   `ReconciliationAlert` and notify.

### Tradeoffs
See the [Design tradeoffs table in README.md](README.md#design-tradeoffs) for the payment-specific
entries (saga orchestration choice, simulated bank clearing, logging notification stand-ins,
internal-only reconciliation) alongside the trading ones.

## 3a. Accounts, transfers, conversion & loans detail

### Problem
The trading and payment pipelines above both need somewhere real to move money from and to — a
client's actual bank balance, not an abstract label — and a client needs to move money to other
clients at this bank, convert between currencies, and borrow against their account, all while
never being able to touch another client's money.

### Solution
See [docs/ACCOUNTS.md](docs/ACCOUNTS.md) for the full lifecycle, ownership-security model, and
failure scenarios. The core design decisions:

- `Account.balance` is the single source of truth for "how much money is here." Payments
  (`LedgerServiceImpl`), transfers (`TransferServiceImpl`), deposits/withdrawals/conversion
  (`AccountServiceImpl`), and loans (`LoanServiceImpl`) are the *only* writers, each inside one
  `@Transactional` method — never a non-atomic read-then-write split across services.
- `CallerPrincipal` ([security/CallerPrincipal.java](src/main/java/com/dcbate/tradingplatform/security/CallerPrincipal.java))
  is resolved once at the controller boundary from the caller's JWT and passed explicitly into
  services — not read from a static `SecurityContextHolder` inside them, so ownership checks stay
  unit-testable without security-context mocking. `requireOwner()` throws `AccessDeniedException`
  (403) unless the caller owns the resource or holds a staff role.
- `TransferService` (same-bank) is a deliberately separate domain from `PaymentService`
  (cross-bank): a transfer is one atomic DB transaction (no external system can fail after we
  commit), while a payment needs the saga in §3 precisely because bank clearing is external and can
  fail after our data is already written.
- `AccountService.convert()` reuses the FX trading desk's price feed
  (`PriceFeedService.currentPrice()`, §2) for a direct rate lookup — not the order-matching engine,
  since converting your own balance isn't an order waiting to cross another client's.
- `LoanService.accrueInterest()` is scheduled (`LoanInterestScheduler`, cron), not called from the
  API — the same pattern as `ReconciliationScheduler` in §3. Its day-count interest math is a pure,
  directly-unit-tested method, not verified by manipulating wall-clock time.

### Tradeoffs
See the [Design tradeoffs table in README.md](README.md#design-tradeoffs) for the ownership-check
design and the explicit statement that FX order execution doesn't move `Account.balance` yet.

## 4. Low-latency patterns

All six patterns from the spec, with what Phase 1 actually does:

**1. Virtual threads** — `spring.threads.virtual.enabled=true` for the Order API's request
handling; a dedicated `ExecutorService` (`VirtualThreadConfig.virtualThreadExecutor()`) for
WebSocket broadcast fan-out (`OrderStreamHandler`) and for the Matching Engine's poll loop itself.

**2. Lock-free-ish order book** — `ConcurrentHashMap<String, OrderBook>` for currency-pair lookup (truly
lock-free: different currency pairs never block each other). Matching *within* a currency pair is
`synchronized` on that currency pair's `OrderBook` instance — see
[OrderBook.java](src/main/java/com/dcbate/tradingplatform/trading/service/matching/OrderBook.java).
A genuinely wait-free single-pair book (lock-free skip lists, CAS-based price levels) is a much
larger undertaking than Phase 1's scope justifies; per-currency-pair locking already gives full
cross-pair parallelism, which is what horizontal throughput actually depends on.

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

## 5. Reliability pattern: retry + DLQ

The spec's notification retry requirement (exponential backoff, dead-letter queue) is implemented
with Spring Kafka's built-in `@RetryableTopic` on
[NotificationEventConsumer](src/main/java/com/dcbate/tradingplatform/notification/event/NotificationEventConsumer.java),
not a hand-rolled retry loop: `attempts = "6"` (the original try plus five retries),
`@Backoff(delay = 1000, multiplier = 2.0, maxDelay = 16000)` produces exactly the spec's
1s/2s/4s/8s/16s sequence, and `dltTopicSuffix = "-dlq"` matches the spec's `notifications-dlq`
topic name instead of Spring's default `-dlt` suffix. A `@DltHandler` method persists the
terminal failure as a `DEAD_LETTERED` `Notification` row for manual review.

Because the Email/Slack stand-ins are logging-only, there's no real failure mode to trigger this
automatically in practice — see the tradeoffs table. `NotificationServiceImpl.deliver()` lets any
sender exception propagate uncaught specifically so this mechanism *can* act on a real failure
once real providers are wired in (Phase 3); the unit tests prove that propagation, not the
timing.

## 6. Monitoring & observability

Structured logging (`@Slf4j` everywhere), Spring Boot Actuator + Micrometer/Prometheus
(`/actuator/prometheus`), a custom `MatchingEngineHealthIndicator` exposing live order-book depth,
and a `price.anomalies` counter tagged by currency pair. Jaeger tracing and Grafana dashboards are Phase 3.

## 7. Testing strategy

JUnit 5 + Mockito unit tests per service (dependencies mocked, one class per test file), plus
dedicated tests exercising pure logic directly with no framework involved (`OrderBookTest`,
`PaymentVelocityTrackerTest`, `SimulatedBankClearingClientTest`). Two Testcontainers
`@SpringBootTest`s (`OrderFlowIntegrationTest`, `PaymentFlowIntegrationTest`) prove the entire
pipelines against real Kafka, PostgreSQL, and Redis containers — not mocks; the payment one
covers both saga outcomes (settle and compensate) by submitting a normal and an
over-threshold amount. See
[README's coverage section](README.md#test-coverage--stated-honestly) for what's *not* covered
and why.

## 8. Security & compliance

JWT resource-server auth (HS256), `@PreAuthorize` role checks (`CLIENT`/`TRADER`/`ADMIN`/`AUDITOR`/
`COMPLIANCE_OFFICER`) on every endpoint including the payment and account APIs, no secrets
committed (`JWT_SECRET`/`GEMINI_API_KEY`/`CLAUDE_API_KEY` via env vars with local-only
placeholders). On top of role checks, every account/payment/transfer/loan endpoint enforces
**object-level ownership** via `CallerPrincipal` (§3a) — a `CLIENT` token can never see or move
another client's money, proven end-to-end by `AccountSecurityIntegrationTest` against the real (not
`dev`) JWT filter chain. GCP Secret Manager/KMS integration, full OAuth2 identity provider, and
PCI/SOX audit tooling remain scope — this repo's security posture is "correct primitives with real
object-level authorization, not yet production-hardened at the infrastructure layer."

## 9. Deployment & operations

Multi-stage `docker/Dockerfile` (Alpine, non-root user, healthcheck), `docker/docker-compose.yml`
for local Postgres/Redis/Kafka/app, and a `.github/workflows/ci.yml` that runs `mvn verify` and
builds the Docker image on every push. `k8s/` holds production-shaped raw manifests (assume
external managed Postgres/Redis/Kafka); `helm/trading-platform/` is the actually-deployable/testable
chart, with a `devDependencies.enabled` toggle that spins up throwaway single-replica
Postgres/Redis/Kafka for local testing — verified end to end against a local `kind` cluster
(all pods `Running`, liveness/readiness probes reporting `UP`). GCP deployment and a production
CI/CD promotion pipeline remain scope.

## 10. Future improvements

- Distribute `OrderVelocityTracker`/`PaymentVelocityTracker` via Redis so velocity limits hold
  under multiple service instances (today correct only for a single instance each).
- Real SendGrid/Slack/bank-gateway integrations, replacing the logging/simulated stand-ins.
- Wire FX order execution (the matching engine) into real `Account.balance` movement — today only
  payments, transfers, deposits/withdrawals, and conversion touch it (§3a).
- Benchmark thread affinity and Chronicle Queue against the spec's latency targets — targets
  are design intent until Gatling load tests exist.
- Jaeger tracing, Grafana dashboards, and GCP Secret Manager/KMS integration.
