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
the ledger nets back to zero. I made `SimulatedBankClearingClient` deterministically fail above a
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

### "What if Kafka goes down?"

`KafkaEventPublisher` catches both an async send failure and the synchronous case — a producer
blocking on missing topic metadata, which I cap at 3 seconds via `max.block.ms` (a real bug I found
and fixed during the Spring Boot 4.1.0 migration, see `KAFKA_SETUP.md`) — and queues the event in
an in-memory buffer, retried every 5 seconds until Kafka recovers. I'll be honest about the limits
of this rather than oversell it: it's single-instance and in-memory, so queued events are lost on a
restart, and it doesn't help once there's more than one app instance running. It buys me time
through a transient outage; it isn't a durable outbox pattern, and I wouldn't claim it is.

### "What if the fraud-detection AI (Gemini) is down?"

Nothing about the fraud decision changes, and I want to be precise here because the obvious-sounding
answer — "payments get flagged for manual review instead" — is actually wrong for how I built this.
`FraudDetectionServiceImpl.evaluate()` runs three deterministic rule checks (velocity,
country-change, amount-anomaly) and decides BLOCKED / UNDER_REVIEW / PASS from those alone.
`GeminiAnomalyDetector` only gets invoked *after* that decision is already made, purely to generate
a human-readable explanation string for the record and the notification text. If Gemini times out
or errors, it falls back to the plain rule-description text — the payment's actual status doesn't
move either way. I did that on purpose: AI enrichment is cosmetic to the decision here, not
load-bearing, because I didn't want a third-party API outage to be able to change whether money
moves.

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

### "What's actually simulated in this system, and why?"

I'd answer this directly, not defensively. Email and Slack notifications are logged, not sent to a
real provider — a SendGrid/Slack integration is roughly an hour of work behind the same interface I
already built. The bank clearing gateway is a deterministic amount-threshold simulation, not a real
correspondent-bank connection. The FX price feed is a bounded random walk, not real market data.
There's no real login/registration flow — JWTs are hand-issued in tests or bypassed entirely via a
`dev` profile. One gap I'd flag as a genuine scope cut rather than a defensible design choice: FX
trade fills don't currently move account balances at all — `ExecutionServiceImpl` has no reference
to `AccountService`, and I ran out of runway before wiring that up. Full reasoning for each, and
what productionizing would take, in `DESIGN_DECISIONS.md`.
