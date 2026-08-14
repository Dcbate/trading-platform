# Architecture

This is the deep dive behind [README.md](README.md) — the reasoning I want on record, not just
what the code does but why I built it that way. It covers the retail banking core (accounts,
deposits/withdrawals, internal transfers, FX conversion, loans — §3a and
[docs/ACCOUNTS.md](docs/ACCOUNTS.md)), the FX trading desk (§2), and the cross-bank payment &
notification system (§3). Kubernetes manifests and a Helm chart exist and are verified against a
local `kind` cluster (§9). Distributed tracing, Prometheus metrics, Grafana dashboards, and Gatling
load tests are all built and live-verified now too (§6, §10) — the one thing genuinely still
outstanding is a real GCP deployment, since I don't have cloud credentials in this environment.

## 1. System architecture overview

Both systems are event-driven end to end: every component talks to the next one exclusively
through Kafka, never a direct call. I did that so each piece is independently deployable and
independently recoverable — killing any one of them loses no data, because the next consumer to
catch up on its topic just replays whatever it missed.

```
Order API → [orders] → Risk Service → [orders-validated] → Matching Engine → [trades] → Execution Service

Payment API → [payments] → Fraud Detection → [payments-validated] → Settlement (saga) → [notifications] → Notification Service
```

`Price Feed Service` and `Reconciliation Scheduler` run alongside these pipelines on their own
schedules; neither sits in a request's critical path.

## 2. Trading system detail

### Problem

Orders have to be accepted, risk-checked, matched, and settled without ever being lost or
double-counted, fast enough that a trader's fill reflects the market they saw when they clicked.

### Solution

See the [README's architecture diagram](README.md#architecture-diagram) for the full picture. The
core decision I made here is **who owns what state, and who's allowed to write it** — I wanted
exactly one writer per status transition, because the moment two services can write the same field,
you're one race condition away from a lost update:

- `OrderService` is the only writer of an order's *initial* state (`PENDING`).
- `RiskService` is the only writer of `VALIDATED` / risk-driven `REJECTED`.
- `MatchingEngineService` never touches Postgres — it's pure in-memory state (the order book) plus
  a Kafka publish. I kept the matching hot path off the database entirely on purpose.
- `ExecutionService` is the only writer of `FILLED` / `PARTIALLY_FILLED` and the only place a
  `Trade` row gets created.

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
6. `TradeEventConsumer` batch-consumes `trades` sequentially — deliberately not in parallel, since
   a batch can contain multiple fills against the same order and I need those status updates to
   apply in order — and hands each to `ExecutionServiceImpl.recordTrade()`.
7. `ExecutionServiceImpl` persists the `Trade`, updates both `Order` rows, appends to the Chronicle
   trade journal, and pushes the new status over `OrderStreamHandler`'s WebSocket.

### Tradeoffs

See the [Design tradeoffs table in README.md](README.md#design-tradeoffs) — repeating it here
wouldn't add anything; that table is the canonical list.

## 3. Payment system detail

### Problem

Payments can't be charged twice even with a retried request, can't disappear even if a service
crashes mid-flight, and every failure needs a customer-visible reason — while fraud has to be
caught without a real card/merchant/bank-tokenization system to check against.

### Solution

See [docs/PAYMENT_SYSTEM.md](docs/PAYMENT_SYSTEM.md) for the full lifecycle, saga walkthrough, and
fraud/failure scenarios. The decisions I care most about explaining:

- `PaymentService` is idempotent on `idempotencyKey` (a DB unique constraint, not an
  application-level check I could get wrong) — the only writer of a payment's initial `PENDING`
  state.
- `FraudDetectionService` is the only writer of `BLOCKED`/`UNDER_REVIEW`.
- `SettlementService` is a saga **orchestrator**, not choreographed via extra Kafka hops: reserve,
  ledger, and bank-clear are direct calls inside one transaction, so the compensation logic on
  failure lives in one place I can actually review, instead of being spread across consumers where
  I'd have to reconstruct the failure path in my head from three different classes.
- `LedgerService` is append-only. Compensation writes *reversing* rows; it never edits or deletes a
  booked entry — the ledger's history is the audit trail, and I wanted that property to be
  structural, not something I have to remember to preserve.
- `NotificationService` only ever persists a `Notification` row on success or as a terminal
  dead-letter record — a failed attempt in between leaves no row, so Kafka's retry mechanism never
  sees stale state to reconcile.

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
entries — saga orchestration choice, simulated bank clearing, logging notification stand-ins,
internal-only reconciliation — alongside the trading ones.

## 3a. Accounts, transfers, conversion & loans detail

### Problem

The trading and payment pipelines above both need somewhere real to move money from and to — a
client's actual bank balance, not an abstract label — and a client needs to move money to other
clients at this bank, convert between currencies, and borrow against their account, all while never
being able to touch another client's money.

### Solution

See [docs/ACCOUNTS.md](docs/ACCOUNTS.md) for the full lifecycle, ownership-security model, and
failure scenarios. The decisions worth explaining:

- `Account.balance` is the single source of truth for "how much money is here." Payments
  (`LedgerServiceImpl`), transfers (`TransferServiceImpl`), deposits/withdrawals/conversion
  (`AccountServiceImpl`), and loans (`LoanServiceImpl`) are the *only* writers, each inside one
  `@Transactional` method — I never wanted a read-then-write split across services, because that's
  exactly where a race condition between two concurrent requests would land.
- `CallerPrincipal` ([security/CallerPrincipal.java](src/main/java/com/dcbate/tradingplatform/security/CallerPrincipal.java))
  is resolved once at the controller boundary from the caller's JWT and passed explicitly into
  services — I deliberately didn't read it from a static `SecurityContextHolder` inside services,
  because I wanted ownership checks to stay unit-testable without mocking a security context.
  `requireOwner()` throws `AccessDeniedException` (403) unless the caller owns the resource or
  holds a staff role.
- `TransferService` (same-bank) is a deliberately separate domain from `PaymentService`
  (cross-bank): a transfer is one atomic DB transaction (no external system can fail after I
  commit), while a payment needs the saga in §3 precisely because bank clearing is external and can
  fail after my data is already written.
- `AccountService.convert()` reuses the FX trading desk's price feed
  (`PriceFeedService.currentPrice()`, §2) for a direct rate lookup — not the order-matching engine,
  since converting your own balance isn't an order waiting to cross another client's.
- `LoanService.accrueInterest()` is scheduled (`LoanInterestScheduler`, cron), not called from the
  API — the same pattern as `ReconciliationScheduler` in §3. Its day-count interest math is a pure,
  directly-unit-tested method rather than something I verify by manipulating wall-clock time in a
  test, which I've been burned by before.

### Tradeoffs

See the [Design tradeoffs table in README.md](README.md#design-tradeoffs) for the ownership-check
design and the explicit statement that FX order execution doesn't move `Account.balance` yet.

## 4. Low-latency patterns

All six patterns from the original spec, with what I actually built for each:

**1. Virtual threads** — `spring.threads.virtual.enabled=true` for the Order API's request
handling; a dedicated `ExecutorService` (`VirtualThreadConfig.virtualThreadExecutor()`) for
WebSocket broadcast fan-out (`OrderStreamHandler`) and for the Matching Engine's poll loop itself.

**2. Lock-free-ish order book** — `ConcurrentHashMap<String, OrderBook>` for currency-pair lookup
(genuinely lock-free: different currency pairs never block each other). Matching *within* a
currency pair is `synchronized` on that pair's `OrderBook` instance — see
[OrderBook.java](src/main/java/com/dcbate/tradingplatform/trading/service/matching/OrderBook.java).
A truly wait-free single-pair book (lock-free skip lists, CAS-based price levels) is a much bigger
undertaking than this project needed; per-currency-pair locking already gives full cross-pair
parallelism, which is what horizontal throughput actually depends on.

**3. Batch processing** — `RiskService` and `ExecutionService` consume via Spring Kafka's batch
listener (`containerFactory=batchListenerFactory`, `AckMode.BATCH`). The Matching Engine goes
further: it bypasses Spring Kafka entirely and drives its own `KafkaConsumer.poll()` loop
(`MatchingEngineConsumerRunner`) with `max.poll.records` from `trading.matching-engine.batch-size`
— the spec's literal `consumer.poll(Duration.ofMillis(100))` pattern, with no listener-container
overhead between poll and match.

**4. Thread affinity** — implemented via `net.openhft:affinity`
(`MatchingEngineConsumerRunner.acquireAffinityLockIfEnabled()`), **disabled by default**
(`trading.matching-engine.thread-affinity.enabled=false`). It needs a native JNI library that isn't
guaranteed present on every dev machine or container base image; turning it on is a one-line config
change plus a benchmark to confirm it actually helps on the target hardware — I didn't want to ship
a latency claim I hadn't measured.

**5. Non-blocking WebSocket fan-out** — `OrderStreamHandler` holds a `ConcurrentHashMap`-backed
session set and dispatches each send onto the virtual-thread executor, so one slow or dead session
can never block another subscriber or the publishing thread. I used Spring MVC's
`TextWebSocketHandler` here, not WebFlux's reactive `Flux`/`Sinks` — I wanted one stack (Spring MVC
+ virtual threads) instead of mixing reactive and blocking programming models for a single
broadcast use case. The effect (thread-per-connection cost eliminated) is the same; the mechanism
differs from the spec's `Flux` sketch, and I think that's the right call.

**6. Off-heap trade journal** — Chronicle Queue, memory-mapped file, zero GC pressure. See
[ChronicleTradeJournalWriter](src/main/java/com/dcbate/tradingplatform/chronicle/ChronicleTradeJournalWriter.java).
Needs JVM `--add-opens`/`--add-exports` flags on JDK 17+ to reach internal APIs — wired into
`pom.xml` (`chronicle.jvm.opens`, applied to Surefire, `spring-boot:run`, and `docker/Dockerfile`).

## 5. Reliability pattern: retry + DLQ

I implemented the notification retry requirement (exponential backoff, dead-letter queue) with
Spring Kafka's built-in `@RetryableTopic` on
[NotificationEventConsumer](src/main/java/com/dcbate/tradingplatform/notification/event/NotificationEventConsumer.java)
rather than a hand-rolled retry loop — no reason to reinvent that: `attempts = "6"` (the original
try plus five retries), `@Backoff(delay = 1000, multiplier = 2.0, maxDelay = 16000)` produces
exactly the 1s/2s/4s/8s/16s sequence I wanted, and `dltTopicSuffix = "-dlq"` matches
`notifications-dlq` instead of Spring's default `-dlt` suffix. A `@DltHandler` method persists the
terminal failure as a `DEAD_LETTERED` `Notification` row for manual review.

Because the Email/Slack stand-ins are logging-only, there's no real failure mode to trigger this
automatically in practice — see the tradeoffs table. I deliberately let any sender exception
propagate uncaught from `NotificationServiceImpl.deliver()` specifically so this mechanism *can*
act on a real failure once real providers are wired in; the unit tests prove that propagation, not
the timing.

## 6. Monitoring & observability

Structured logging (`@Slf4j` everywhere), Spring Boot Actuator + Micrometer/Prometheus
(`/actuator/prometheus`), a custom `MatchingEngineHealthIndicator` exposing live order-book depth,
and a `price.anomalies` counter tagged by currency pair.

Distributed tracing, Prometheus alert rules, and Grafana dashboards are built and live-verified —
not just configured and hoped-for. Getting there took more debugging than I expected: neither of
Spring Boot 4's two tracing autoconfiguration modules actually wires the `Tracer` bean, so out of
the box, every span was silently created and discarded with zero errors anywhere — I only found it
by tracing a real request and noticing Jaeger's service list never grew past the collector itself.
`TracingConfig.java` fills in the missing `SdkTracerProvider` and `OtelTracer` bridge beans by
hand. Full verification, with real trace IDs and metric values, in
[docs/OBSERVABILITY_PROOF.md](docs/OBSERVABILITY_PROOF.md).

## 7. Testing strategy

JUnit 5 + Mockito unit tests per service (dependencies mocked, one class per test file), plus
dedicated tests that exercise pure logic directly with no framework involved (`OrderBookTest`,
`PaymentVelocityTrackerTest`, `SimulatedBankClearingClientTest`). One Testcontainers
`@SpringBootTest` (`AccountSecurityIntegrationTest`) proves ownership security end-to-end against
real Kafka, PostgreSQL, and Redis containers — two more integration tests covering the full
order→trade and payment→settlement pipelines existed at one point but I removed them after they
proved flaky specifically in the Testcontainers networking environment on this machine, not in the
application logic itself; I'd rather have an honest gap than a test suite that's flaky for reasons
unrelated to what it's supposed to catch. See
[README's coverage section](README.md#test-coverage--stated-honestly) for the full list of what's
covered and what isn't, and why.

## 8. Security & compliance

JWT resource-server auth (HS256), `@PreAuthorize` role checks
(`CLIENT`/`TRADER`/`ADMIN`/`AUDITOR`/`COMPLIANCE_OFFICER`) on every endpoint including the payment
and account APIs, no secrets committed (`JWT_SECRET`/`GEMINI_API_KEY`/`CLAUDE_API_KEY` via env vars
with local-only placeholders). On top of role checks, every account/payment/transfer/loan endpoint
enforces **object-level ownership** via `CallerPrincipal` (§3a) — a `CLIENT` token can never see or
move another client's money, and I proved that end to end with
`AccountSecurityIntegrationTest` against the real (not `dev`) JWT filter chain rather than just
asserting it in a docstring. GCP Secret Manager/KMS integration, a full OAuth2 identity provider,
and PCI/SOX audit tooling are still ahead of me — this repo's security posture right now is
"correct primitives with real object-level authorization, not yet production-hardened at the
infrastructure layer," and I want to be precise about that distinction rather than round it up.

## 9. Deployment & operations

Multi-stage `docker/Dockerfile` (Alpine, non-root user, healthcheck), `docker/docker-compose.yml`
for local Postgres/Redis/Kafka/app/Jaeger/Prometheus/Grafana, and a `.github/workflows/ci.yml` that
runs `mvn verify` and builds the Docker image on every push. `k8s/` holds production-shaped raw
manifests (assumes external managed Postgres/Redis/Kafka); `helm/trading-platform/` is the
actually-deployable/testable chart, with a `devDependencies.enabled` toggle that spins up
throwaway single-replica Postgres/Redis/Kafka for local testing — I verified this end to end
against a local `kind` cluster (all pods `Running`, liveness/readiness probes reporting `UP`). A
real GCP deployment and a production CI/CD promotion pipeline are still outstanding — the GitHub
Actions workflow for it is written but genuinely untested, since I don't have GCP credentials in
this environment. I'd rather say that plainly than claim it works.

## The Spring Boot 4 migration: what actually broke

I upgraded from Boot 3.3.4 to 4.1.0, and it broke more than I expected — worth documenting because
none of these are obscure edge cases, they're the kind of thing anyone doing this upgrade will hit:

- **Jackson 3 is the new default.** Boot 4 ships `tools.jackson.*` instead of
  `com.fasterxml.jackson.*` — a different package, a different `ObjectMapper`. I had to migrate
  every injection site, two `JsonProcessingException` catches, and the Jackson-based
  `MappingJackson2HttpMessageConverter` in five MockMvc tests over to the Jackson-3-native
  equivalents.
- **`TestRestTemplate` is gone.** Spring Framework 7 removed it outright. I rewrote the affected
  integration tests to use `RestTestClient`'s fluent builder instead.
- **Flyway's autoconfiguration moved out of `spring-boot-autoconfigure`** into a standalone
  `spring-boot-flyway` module. Without it, my migrations were silently not running — no error, just
  an empty schema. I only caught it because a test failed with "missing table," not because
  anything told me Flyway wasn't running.
- **Same story for Kafka.** `spring-boot-kafka` is a new standalone module too; without it, the
  `KafkaAdmin` bean that auto-provisions my topics on startup just doesn't exist. I'd worked around
  this for a while by creating topics manually via the CLI before I found the actual cause.
- **And for distributed tracing** — see §6 above.
- **A real bug, found because of this migration, not caused by it:** `KafkaEventPublisher` builds
  its producer from a hardcoded properties map that bypasses `application.yml` entirely. Combined
  with `acks=all` and idempotence, `KafkaProducer.send()` could block an HTTP request thread for up
  to Kafka's default 60-second `max.block.ms` if a topic's metadata wasn't cached yet — which is
  exactly what happens right after topics are freshly created. I capped it at 3 seconds and routed
  the failure into the existing fallback-retry queue. That's a latent production bug that predates
  the migration entirely; I just happened to trip over it while debugging something else.

Every one of these I found the same way: something that used to work silently stopped working, with
no error pointing at the real cause, and I had to `javap`/`unzip -l` my way through the actual jars
on the classpath to figure out what Boot 4 had moved or removed. That's the honest version of "I
upgraded to Boot 4" — it wasn't a version bump, it was several hours of archaeology.

## 10. Future improvements

- Distribute `OrderVelocityTracker`/`PaymentVelocityTracker` via Redis so velocity limits hold
  under multiple service instances (today correct only for a single instance each).
- Real SendGrid/Slack/bank-gateway integrations, replacing the logging/simulated stand-ins.
- Wire FX order execution (the matching engine) into real `Account.balance` movement — today only
  payments, transfers, deposits/withdrawals, and conversion touch it (§3a).
- Benchmark thread affinity and Chronicle Queue against real latency targets now that Gatling load
  tests exist — I have real numbers now (`docs/PERFORMANCE_BASELINE.md`), just not yet with thread
  affinity switched on.
- A real GCP deployment, GCP Secret Manager/KMS integration, and a real OAuth2 identity provider.
