# Payment System

Deep dive into the payment lifecycle, the settlement saga, and the fraud/reconciliation rules.
This is the **cross-bank** path — "pay other banks money." For paying another client at *this*
bank (instant, no saga) see [docs/ACCOUNTS.md §5](ACCOUNTS.md#5-internal-transfers--pay-other-users-money);
for the broader system design, see [ARCHITECTURE.md](../ARCHITECTURE.md).

Every payment now debits a real client `Account` (see [docs/ACCOUNTS.md](ACCOUNTS.md)):
`PaymentRequest.sourceAccountId` must reference an `ACTIVE` account owned by `clientId` with a
sufficient balance, checked at submission (`PaymentServiceImpl`) and re-checked defensively at
ledger-booking time (`LedgerServiceImpl`) — a point-in-time check, not a fund hold, so two
concurrent payments from the same account could theoretically both pass the first check before
either reaches the second; that second check is what actually prevents an overdraft. Insufficient
funds is a 409 (`InsufficientFundsException`) before anything reaches Kafka.

## 1. Payment lifecycle

```
PENDING → RESERVED → SETTLED
   │           │
   │           └──→ FAILED   (bank clearing failed → compensated)
   │
   └──→ UNDER_REVIEW  (fraud: amount anomaly — held for compliance-officer approve/reject)
   └──→ BLOCKED        (fraud: velocity or country-change — terminal)
```

- `PENDING`: written by `PaymentService` the instant the payment is accepted, before fraud
  checks or settlement. `GET /v1/payments/{id}` can return this immediately after `202`.
- `UNDER_REVIEW`: written by `FraudDetectionService`, held until a `COMPLIANCE_OFFICER` calls
  `POST /v1/payments/{id}/approve` (releases the withheld `PaymentValidatedEvent`, settlement
  proceeds) or `/reject` (moves to terminal `BLOCKED`).
- `BLOCKED`: written by `FraudDetectionService` directly (velocity/country-change) or via a
  compliance rejection. Terminal either way.
- `RESERVED`: written by `SettlementService` the moment the saga starts, before the ledger or
  bank-clearing steps run.
- `SETTLED` / `FAILED`: written by `SettlementService` at the end of the saga, depending on
  whether bank clearing succeeded.

## 2. Idempotency

`POST /v1/payments` is idempotent on `idempotencyKey`: `PaymentServiceImpl.submitPayment()`
checks for an existing payment with that key first (a DB unique constraint backs this — see
`V2__payments_schema.sql`) and returns it unchanged rather than creating a duplicate. A client
retrying a timed-out request is safe to resubmit with the same key.

## 3. The settlement saga

[`SettlementServiceImpl`](../src/main/java/com/dcbate/tradingplatform/payment/service/SettlementServiceImpl.java)
is an **orchestrator** — not choreographed via extra Kafka round-trips. Each step is a direct
call inside one transaction:

1. **Reserve**: `Payment.status = RESERVED`, a `Settlement{status=IN_PROGRESS}` row is created.
2. **Ledger**: `LedgerService.recordDoubleEntry()` writes one DEBIT (the client's account) and
   one CREDIT (the platform account), both referencing the payment, and archives both to
   `ledger-entries` for compliance (365-day retention — nothing consumes this topic yet, it's a
   forward-looking audit sink per the original spec).
3. **Clear**: `BankClearingClient.clear()`. No real bank exists for Phase 2, so
   `SimulatedBankClearingClient` deterministically fails above
   `payment.settlement.simulated-bank-failure-threshold` — a test seam, not a business rule, that
   exists specifically so compensation is exercisable and tested rather than theoretical.
4. **Compensate on failure**: `LedgerService.reverseEntries()` writes *reversing* rows (opposite
   DEBIT/CREDIT, same accounts and amounts) — the ledger never edits or deletes, only appends.
   `Settlement.status = COMPENSATED`, `Payment.status = FAILED`.

Either outcome publishes a `NotificationEvent` to `notifications`.

## 4. Example walkthrough

1. `POST /v1/payments {clientId: "payer-1", amount: 250.00, country: "US", idempotencyKey: "..."}`
   → `202`, `Payment{status=PENDING}`.
2. `FraudDetectionService` checks velocity, country-change, and amount-vs-average — all clear →
   `PaymentValidatedEvent` → `payments-validated`.
3. `SettlementService` reserves, records a DEBIT/CREDIT pair for $250.00, clears (below the
   simulated threshold) → `Payment.status = SETTLED`.
4. `NotificationService` sends (via the logging Email/Slack stand-ins) — a plain success needs no
   AI narration, so `ClaudeSummarizer` is skipped for this outcome.

A failure variant: the same flow with `amount: 600000.00` clears fraud (nothing in the rules
cares about absolute amount beyond the 3× client-average check, and a first-time client has no
average yet) but fails bank clearing → ledger entries are reversed, `Payment.status = FAILED`,
and the failure notification *is* summarized by Claude (or falls back to the plain reason if no
`CLAUDE_API_KEY` is configured).

## 5. Fraud scenarios

Three rules run in [`FraudDetectionServiceImpl`](../src/main/java/com/dcbate/tradingplatform/payment/service/FraudDetectionServiceImpl.java),
first match wins:

- **Velocity** (`BLOCK`): more than `payment.fraud.max-payments-per-window` (default 5) payments
  from the same client within `payment.fraud.window-seconds` (default 60s) — bot-like behavior,
  highest confidence of automated fraud.
- **Country change** (`BLOCK`): the client's previous payment was from a different country less
  than `payment.fraud.country-change-window-hours` (default 2h) ago — "impossible travel," a
  strong compromised-credentials signal.
- **Amount anomaly** (`REVIEW`): the amount exceeds `payment.fraud.amount-multiplier-threshold`
  (default 3×) the client's average *settled* payment. Skipped entirely for a client with no
  settlement history yet (average = 0) — otherwise every first payment would trip this rule.

Dropped from the original spec: "new card + new amount + new merchant." There's no card or
merchant concept in this domain, and inventing one just to satisfy a rule that wouldn't mean
anything would be worse than not having it.

On any flag, `AnomalyDetector` (the same Gemini-backed component the Risk Service uses — see
`ai/AnomalyDetector.java`) enriches the rule's plain-text reason with an AI severity assessment,
purely advisory: the rule already decided BLOCK/REVIEW before the AI call happens.

## 6. Failure scenarios

| Failure | Behavior |
|---|---|
| Fraud/Settlement/Notification service crashes | Kafka retains the record; consumer resumes from last committed offset, nothing lost |
| Bank clearing fails | Ledger entries reversed, payment `FAILED`, customer notified with the reason |
| Notification delivery fails | `@RetryableTopic` retries at 1s/2s/4s/8s/16s (5 retries, 6 attempts total); exhausted retries land on `notifications-dlq` and are recorded as `DEAD_LETTERED` for manual review |
| Gemini/Claude API unavailable or unset | Fraud flags and notifications still work using the rule's plain-text reason; AI enrichment is skipped, never required |
| Reconciliation finds a discrepancy | <$1 auto-resolves (logged); larger discrepancies persist a `ReconciliationAlert` and notify — never auto-resolved silently |
