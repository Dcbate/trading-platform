# Operations Runbook

I grounded this entirely in what's actually deployed and verified in this repo — the 5 alert rules
below are real (`docker/prometheus/alerts.yml`), and I confirmed them loaded and registered in a
running Prometheus instance while writing this (previously they weren't: the compose file only
mounted `prometheus.yml` and never mounted `alerts.yml`, so the rules existed on disk but were never
actually evaluated — I fixed that as part of writing this doc). Dashboards referenced are the real
panels in `docker/grafana/dashboards/banking-platform.json`, verified live in
`OBSERVABILITY_PROOF.md`.

## Monitoring & alerts

| Alert | Condition | Severity | What it means |
|---|---|---|---|
| `TransferLatencyP99High` | internal transfer p99 > 500ms for 2m | **page** | Same-bank transfers are slow — check DB connection pool and recent deploys first |
| `PaymentSuccessRateLow` | settlement success rate < 99% over 15m | investigate | More payments failing/reversing than usual — check `SimulatedBankClearingClient`'s threshold or a real clearing outage in production |
| `FraudDetectionLatencyHigh` | fraud check p99 > 2s for 2m | investigate | Almost always the Claude API being slow, not the app — see the incident example below |
| `KafkaConsumerLagHigh` | any consumer group lag > 1000 messages for 5m | **page** | A consumer has stalled or can't keep up — customer-visible (payments/orders stop progressing) |
| `DbConnectionPoolNearExhaustion` | HikariCP pool > 90% utilized for 5m | **page** | Connection leak or genuine load spike — next failure mode is request timeouts across the whole app |

**Where to look first, always:** Grafana `Banking Platform` dashboard
(`http://localhost:3000/d/banking-platform`) for the shape of the problem, then Jaeger
(`http://localhost:16686`) for a concrete slow/failed trace, then `docker logs
trading-platform-app-1` for the exception if traces don't explain it.

## On-call procedures

### "A payment/transfer is stuck"

1. `GET /v1/payments/{id}` (or `/v1/transfers/{id}`) — what status is it actually in?
   `PENDING` past a few seconds is the anomaly; `UNDER_REVIEW` is a normal fraud-hold outcome, not
   a bug.
2. If `PENDING`: check `KafkaConsumerLagHigh` in Grafana/Prometheus — the `payments` or
   `payments-validated` topic's consumer group has likely stalled. `docker exec
   trading-platform-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server
   localhost:9092 --describe --group fraud-detection-service` (or `settlement-service`) shows the
   actual lag per partition.
3. If `UNDER_REVIEW`: this is `FraudDetectionServiceImpl` correctly holding it — resolve via
   `POST /v1/payments/{id}/approve` or `/reject` (requires `COMPLIANCE_OFFICER`), not a bug to fix.
4. Find the request in Jaeger (service `trading-platform`, operation `http post /v1/payments`) —
   the span breakdown shows exactly which stage (validation, saga step, ledger write) is slow.

### "Fraud detection is slow"

This is almost always the real Claude API (`AnthropicAnomalyDetector`), not the application — check
the `Fraud detection latency` Grafana panel and the `fraud_detection_latency_seconds` metric first.
I built `AnthropicAnomalyDetector` to degrade gracefully (falls back to the plain rule-based
decision on any failure or timeout, never blocking the fraud verdict itself — see
`DESIGN_DECISIONS.md`), so a slow or down Claude API shows up as elevated latency, **not** as failed
payments. If it's consistently slow: nothing urgent needs fixing in the app; consider lowering the
enrichment timeout (currently 2000ms, shared with the payment-summary and Game Mode debrief
integrations via `claude.timeout-ms`) if the tail is unacceptable, or removing the `CLAUDE_API_KEY`
env var temporarily to force the fast fallback path while Claude recovers.

### "Kafka is down / a broker is unreachable"

`KafkaEventPublisher` already handles this: I built it so a failed or timed-out send (capped at 3s
via `max.block.ms`, see `KAFKA_SETUP.md`) gets queued in an in-memory fallback buffer and retried
every 5 seconds (`kafka.fallback-queue.drain-interval-ms`) until it succeeds. Check
`kafka_fallback_queue_size` in Prometheus — a non-zero and growing value confirms this path is
active. I want to be clear this is a **single-instance, in-memory** safety net (documented in
`KafkaEventPublisher`'s javadoc): queued events are lost on restart, and it doesn't help a
multi-instance deployment where only one instance can see the outage. It buys time for Kafka to
recover; I wouldn't call it a substitute for actually fixing a prolonged broker outage.

### "I need to debug a duplicate transfer/payment"

Payments are idempotent on a client-supplied `idempotencyKey`
(`PaymentRepository.findByIdempotencyKey`, checked before creating a new row) — a genuine retried
POST with the same key returns the existing payment, not a duplicate. If two *different* payments
moved the same money: query the `ledger_entries` table directly for the account — the ledger is
double-entry, so debits and credits must net to zero for every payment; any imbalance is the bug,
not the account balance itself. Cross-check against the Chronicle trade journal only if the
discrepancy involves an FX-adjacent account — the journal is compliance-oriented (trades only),
not a general ledger, so it usually isn't the first place to look for payment/transfer issues.

## Escalation

- **Level 1 (on-call engineer):** runbook procedures above — covers stuck payments, fraud latency,
  Kafka backlog, ledger verification.
- **Level 2 (database team):** ledger imbalance that isn't explained by an in-flight saga step,
  Postgres connection pool exhaustion that isn't resolved by scaling the pool, or any suspicion of
  actual data corruption.
- **Level 3 (external dependency):** an Anthropic API outage lasting beyond what the
  graceful-degradation fallback should reasonably absorb; a real bank-clearing-partner outage once
  `SimulatedBankClearingClient` is replaced with a real integration (see `DESIGN_DECISIONS.md`).

## Known operational limitations, stated directly

- The Kafka fallback queue is in-memory and single-instance — not a durable store, and it doesn't
  help once you run more than one app instance. I've flagged this in the code and here, not hidden
  it.
- `SimulatedBankClearingClient`'s $500,000 failure threshold is a deterministic test seam I built,
  not a real risk control — see `DESIGN_DECISIONS.md` before treating a "failed" payment above that
  amount as a real clearing failure in this environment.
- I haven't wired up a production alerting *destination* (no PagerDuty/Opsgenie integration) — the 5
  Prometheus alert rules fire and are visible in Prometheus's own UI/API, but nothing pages anyone
  yet. That's the next piece I'd add once Alertmanager (or an equivalent) is in the stack.
