# Interview Talking Points

I checked every answer here against the actual running code, not against memory or an old plan.
Where an earlier draft of my own planning notes had a detail slightly wrong — loan interest
compounding, what happens when the fraud AI is down — the answer below is the corrected, verified
version, with the file I'd point to if someone wanted proof.

### "Walk me through a transfer"

A same-bank transfer is the simple case, and I built it that way on purpose. `TransferServiceImpl`
does an ownership check, verifies both accounts are `ACTIVE` and the same currency, then debits one
account and credits the other **inside one `@Transactional` method, in the same database** — I
didn't need a saga here, because there's no external system that can partially fail. I publish a
`TransferEvent` to Kafka afterward for audit, but the money movement itself is already committed by
that point. I'd contrast this with a cross-bank payment right away — that distinction is basically
the answer to the next question.

### "Why do cross-bank payments need a saga but internal transfers don't?"

Because an external bank's clearing API is a separate system that can fail, time out, or succeed
partially, and you can't put "call another bank" inside a local database transaction. In
`PaymentServiceImpl` I reserve funds, then `SettlementServiceImpl` runs the saga: write ledger
entries, call `BankClearingClient.clear()`, and on failure write compensating reversal entries so
the ledger nets back to zero. I made `BankClearingClientImpl` deterministically fail above a
$500,000 threshold specifically so I could exercise both the settle and the compensate paths on
demand instead of only in theory.

### "How does loan interest accrual actually work?"

Simple daily interest, not compounding — I do
`outstandingPrincipal × (annualRatePercent/100) × (daysSinceLastAccrual/365)` in
`LoanServiceImpl.calculateAccrual()`, run via a scheduled job against every `ACTIVE` loan. I compute
it on `outstandingPrincipal` each time, not on principal-plus-previously-accrued-interest, so
there's no interest-on-interest. I apply repayments to `accruedInterest` first, then
`outstandingPrincipal` — real amortization order — and I cap a repayment at total owed so it's
impossible to overpay.

### "Why Chronicle Queue for the trade compliance journal?"

Because it writes off-heap, memory-mapped, so a GC pause can't delay or interrupt a compliance
write — and that matters specifically for a matching engine, where GC pauses are a real latency
source. I keep it deliberately separate from both Postgres and the `trades` Kafka topic — three
independent records of the same fact, which is the point for an audit trail: no single component's
failure erases the record.

### "How do you prevent one customer from accessing another's account?"

Two layers, and I think the distinction is the interesting part: `@PreAuthorize` role checks prove
the caller is *some* authenticated client, not that they own the specific resource they're
touching. I closed that gap with `CallerPrincipal` — derived from the validated JWT's subject claim
— plus a `requireOwner()` check on every account/payment/transfer/loan service method, which throws
`AccessDeniedException` (403) if the caller isn't staff and isn't the resource's owner. I didn't
just assert this works — I proved it with `AccountSecurityIntegrationTest` against the real
(non-`dev`) JWT filter chain: two hand-signed tokens, and client A genuinely gets a 403 reading
client B's account.

### "What security issue did you find and fix in your own code?"

Two, both in the auth flow, both the kind of thing that's easy to miss because the happy path still
works fine either way. First: `jwt.secret` had a committed placeholder default meant for local dev,
and nothing stopped it from being the *real* secret if `JWT_SECRET` was ever left unset in an actual
deployment — the app would start up normally and silently sign forgeable tokens with a value sitting
in plaintext in `application.yml`. I added a startup check in `JwtIssuer` that refuses to start with
that placeholder outside the `dev`/`test` profiles, so a misconfigured deployment fails loudly
instead of running insecurely. Second: logout only ever cleared cookies client-side — the refresh
token itself stayed valid server-side for up to 7 days after "logging out." I wired
`AuthController.logout()` to actually revoke the refresh-token row, and proved it live: logged in,
logged out, then tried to reuse the pre-logout refresh token and got a 401 instead of a fresh
session.

### "What if Kafka goes down?"

`KafkaEventPublisher` catches both an async send failure and the synchronous case — a producer
blocking on missing topic metadata, which I cap at 3 seconds via `max.block.ms` (a real bug I found
and fixed during the Spring Boot 4.1.0 migration, see `KAFKA_SETUP.md`) — and queues the event for
retry every 5 seconds until Kafka recovers. That queue used to be a plain in-memory buffer, and I
was upfront that it didn't survive a restart. I've since backed it with a Chronicle Queue file (same
library as the trade journal) using a named tailer that persists its read position to disk, so a
queued event now survives the app restarting mid-outage too — I proved that by actually killing
Kafka, queuing a deposit, restarting the app container, bringing Kafka back up, and watching the
pre-restart backlog get found and drained. It's still single-instance — a multi-instance deployment
would need this backed by something shared instead — and I'll say that up front rather than let
someone assume otherwise.

One thing testing this taught me: `kafkaTemplate.send()` can throw two different,
similarly-named exception types depending on *how* it fails — `org.springframework.kafka.
KafkaException` for one failure mode, the raw `org.apache.kafka.common.KafkaException` for another
(producer construction itself failing, e.g. the broker hostname not resolving at all). I'd only
caught the Spring one, so that specific failure mode silently escaped uncaught straight past the
fallback queue to the caller as a 500 — the exact case the queue exists to prevent. I only found it
by deliberately taking Kafka down and hitting the API, not by reading the code.

### "What happens to consumers — not just the publisher — when Kafka is down?"

I found this the hard way while testing the durability fix above: with Kafka down at app startup,
the whole app failed to boot, for two separate reasons, both eager, both fixed the same way. First,
`KafkaConsumerLagMetrics`'s constructor called `AdminClient.create()` directly, which resolves the
broker address immediately and throws if it can't — so a Kafka outage at boot crashed Spring's bean
initialization outright. Second, and less obvious: Spring Kafka's `@KafkaListener` containers
default to starting synchronously as part of `ApplicationContext` refresh, so the same outage
crashed startup a second, independent way even after I'd fixed the first one. I fixed both with the
same lazy-connect pattern I already use for the MCP client — never touch Kafka in a constructor —
and for the listener containers specifically, set `autoStartup=false` on both container factories
and added `KafkaListenerStartupRunner`, a scheduled task that starts each container individually
once Kafka's actually reachable, retrying every 5 seconds per container rather than as one
all-or-nothing group. I proved it by killing Kafka, confirming the app now boots healthy anyway,
then bringing Kafka back and checking consumer group membership directly on the broker — all twelve
expected groups (`risk-service`, `matching-engine`, and the rest) showed up with partitions
assigned, with no app restart needed.

### "What if the fraud-detection AI (Claude) is down — or the MCP server it goes through?"

Nothing about the fraud decision changes, and I want to be precise here because the obvious-sounding
answer — "payments get flagged for manual review instead" — is actually wrong for how I built this.
`FraudDetectionServiceImpl.evaluate()` runs three deterministic rule checks (velocity,
country-change, amount-anomaly) and decides BLOCKED / UNDER_REVIEW / PASS from those alone.
`AnomalyDetectorImpl` only gets invoked *after* that decision is already made, purely to generate a
human-readable explanation string for the record and the notification text — and it doesn't call
Claude directly anymore, it calls `bate-mcp-server` (a separate service) over real MCP, which then
calls Claude. That's an extra network hop and an extra failure mode I deliberately took on, so I
made sure the fallback covers both layers: if `bate-mcp-server` is unreachable, the connection
drops mid-call, or Claude itself times out or errors on the other side, `McpToolClient` catches all
of it and returns nothing, and `AnomalyDetectorImpl` falls back to the plain rule-description text —
the payment's actual status doesn't move either way. I did that on purpose: AI enrichment is
cosmetic to the decision here, not load-bearing, because I didn't want a third-party API outage —
or an outage of my own MCP server — to be able to change whether money moves.

### "Why did you use Kafka instead of just calling services directly over REST?"

Two reasons, both real in this codebase. First, decoupling — `RiskService`, `FraudDetectionService`,
the matching engine, and notification delivery all consume off topics independently, so a slow or
down consumer never blocks the producer's HTTP response. Second, the fallback-queue and retry-topic
patterns I built only make sense with an async broker in the middle — a synchronous REST-to-REST
call has no equivalent "queue it and retry in 5 seconds" story unless I built a bespoke outbox
myself.

### "How do you trace a slow transaction?"

I open Jaeger, search by service `trading-platform` and the operation (say, `http post
/v1/transfers`), and the span waterfall shows exactly which named phase — security filter chain,
method-security authorization, the actual business logic — took the time. I verified this for real,
and doing so actually uncovered a genuine Spring Boot 4.1.0 gap I'd mention if asked about the
migration: Boot 4 splits tracing autoconfiguration across two modules, and neither one actually
wires the `Tracer` bean that makes spans get created and exported. Without a hand-written fix,
tracing silently produces zero spans with no error anywhere — I only caught it by tracing a real
request and noticing Jaeger's service list never grew. Full writeup in `OBSERVABILITY_PROOF.md`.

### "Can your system scale to 1M customers?"

I'd answer with evidence, not a guess. My Gatling load tests show 0% failures across 750 requests
spanning transfers, loan origination, and FX order submission, with transfer p99 latency at 67ms —
comfortably under my 500ms target — on a single laptop's Docker Desktop VM sharing CPU with 6 other
containers. That tells me the architecture doesn't have an obvious bottleneck at small scale; it's
not a claim about 1M customers specifically. The honest next step, which I've documented but not
run, is the same simulations at their full sizing (1,000 concurrent users, 100 orders/sec) against
real, dedicated infrastructure instead of a shared local VM.

### "How would you debug a customer who says they lost money?"

In order: I'd query the database for the actual transaction/payment/transfer record and its status,
check the relevant Kafka topic's events if the flow is async, and verify the ledger entries for
that payment actually net to zero — if debits don't equal credits, that's the bug, not the
customer's balance. If the discrepancy still isn't explained, I'd pull the Jaeger trace for the
original request to see which step actually executed versus failed. Worth knowing so you don't go
looking in the wrong place: the Chronicle trade journal is a compliance record for FX **trades**
specifically, not a general-purpose debugging tool for payments or transfers.

### "Do you have circuit breakers anywhere? What if bate-mcp-server goes down?"

Yes, on both genuine external-dependency call sites: `McpToolClient` (calls `bate-mcp-server`) and
`BankClearingClientImpl` (stands in for a real bank gateway). I wired Resilience4j by hand as two
`@Bean`s in `ResilienceConfig.java` rather than its Spring Boot 3 starter — I'd already found two
real Boot-4-compatibility gaps in starters that looked fine (Kafka, tracing), so a small config
class removed that risk entirely for a third one. A 10-call sliding window, tripping at 50%
failures, 30 seconds open, then 3 trial calls before deciding to close or reopen.

The one design decision I'd lead with: only a thrown exception counts as a failure, never a normal
`false` return — `BankClearingClientImpl`'s deterministic "declined above threshold" is a business
outcome, not a sign the gateway's unhealthy, and it must never trip the breaker. I proved that with
a test that fires 10 straight declines through a real breaker and asserts it's still `CLOSED`. And
when the breaker *is* open, `BankClearingClientImpl` fails closed — returns `false` — rather than
letting the exception escape, because the settlement saga only knows "cleared" or "declined," and
assuming success when we couldn't even reach the gateway is the one genuinely unsafe choice.

I live-verified the whole lifecycle, not just unit tests: killed the real `bate-mcp` container,
watched real connection failures accumulate, watched the breaker actually trip
(`CLOSED -> OPEN` in the logs), watched the next scheduled call get skipped before even attempting
a connection, confirmed `state="open"` on the real Prometheus endpoint, brought `bate-mcp` back, and
watched the recovery — `OPEN -> HALF_OPEN` after the cooldown, then `HALF_OPEN -> CLOSED` on the
first successful trial. That live run caught two real bugs in my own wiring that a code read never
would have: `CircuitBreakerRegistry.of(Map.of(name, config))` looks like it binds names to configs
but doesn't — it silently fell back to Resilience4j's 100-call default, which I only caught because
20+ real failures never tripped anything. And my state-transition logger was attached to
`getAllCircuitBreakers()` before either breaker had actually been lazily created, so it logged
nothing at all until I switched it to `registry.getEventPublisher().onEntryAdded(...)`.

### "What's actually simulated in this system, and why?"

I'd answer this directly, not defensively. Email and Slack notifications are logged, not sent to a
real provider — a SendGrid/Slack integration is roughly an hour of work behind the same interface I
already built. The bank clearing gateway is a deterministic amount-threshold simulation, not a real
correspondent-bank connection. The FX price feed is a bounded random walk, not real market data.
Login/signup are real now — real users, bcrypt-hashed passwords, real JWTs — but it's a hand-rolled
issuer rather than a managed identity provider, so no MFA, password reset, or email verification.
One gap I'd flag as a genuine scope cut rather than a defensible design choice: FX
trade fills don't currently move account balances at all — `ExecutionServiceImpl` has no reference
to `AccountService`, and I ran out of runway before wiring that up. Full reasoning for each, and
what productionizing would take, in `DESIGN_DECISIONS.md`.
