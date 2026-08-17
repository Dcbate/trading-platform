# Known Gaps

One place for every known gap in the system, rather than scattered across a dozen docs. I built
this the same way I built everything else here: verified against the actual code, not written from
memory. Where a gap has its own detailed writeup elsewhere, this points there instead of repeating
it — treat this as the index, not the full story.

Three categories, and I've tried to be honest about which is which rather than blur them together:

- **Fixed** — genuinely was a bug, isn't anymore. Kept here so the story of finding and fixing it
  isn't lost, and so nobody re-discovers it and wonders if it's still open.
- **Real gap** — something that should work in a real bank and currently doesn't, stated plainly
  rather than dressed up as a design choice.
- **Deliberate simplification** — scoped out on purpose, with the reasoning for why.

## Fixed

### The matching engine's order book didn't survive an app restart

A resting order (`VALIDATED`, waiting for a crossing counter-order) lived only in an in-memory
`Map<String, OrderBook>` — nothing rebuilt it from Postgres on restart, and the Kafka consumer
feeding it resumes from its last committed offset, not a full replay. An order left resting across
a restart became permanently unmatchable while Postgres still showed it as `VALIDATED` — a real
bug, found live (my own first TSLA buy, from testing something unrelated, turned out to be exactly
this), not by code review.

**Fixed** by `MatchingEngineServiceImpl.recoverRestingOrders()` — a `@PostConstruct` method that
reloads every `VALIDATED`/`PARTIALLY_FILLED` order from Postgres and replays it through the ordinary
`match()` path before the app can process any new order traffic. Verified against a real container
restart: submitted a resting order, restarted the container, confirmed the order was still
`VALIDATED` and the live order-book depth (`/actuator/health`) hadn't dropped, then submitted a
matching order and watched it actually fill. Full story, including the original discovery and the
fix's live proof: [HOW_A_TRADE_FILLS.md](HOW_A_TRADE_FILLS.md).

### Deposits/withdrawals/conversions/loan repayments weren't queryable — only Kafka-published

Only the *current* state was ever queryable (`Account.balance`, `Loan.outstandingPrincipal`) —
the individual events that got you there were published to Kafka for audit and then gone; nothing
consumed or persisted them. A client couldn't see "what happened," only "where things stand now."

**Fixed** by two new tables — `account_activity` and `loan_activity` — written alongside every
existing Kafka publish (same data, now also a row), plus a new unified endpoint,
`GET /v1/statement`, that merges orders, payments, transfers, account activity, and loan activity
into one ownership-checked, chronological feed, exposed in the frontend as its own "Bank Statement"
nav tab. See [ACCOUNTS.md §10](ACCOUNTS.md#10-bank-statement--a-unified-history).

## Real gaps

### Closing an account with a balance doesn't check who owns the destination account

`AccountServiceImpl.closeAccount()` checks that the caller owns the account being *closed*, that
the destination account is `ACTIVE`, and that the two currencies match — but never checks that the
destination account belongs to the caller (or to anyone in particular). Every other money-movement
path in the system enforces ownership on both ends: `convert()` requires the caller own both
accounts, `transfer()` requires the caller own the source account (the recipient is a different
client on purpose — that's the point of a transfer). Closure is the one path where a balance can be
swept into an account the caller doesn't own without going through the `Transfer` domain at all —
found by re-reading `closeAccount()` line by line while building the bank statement (which relies
on the same `AccountActivity` row this produces), not by a dedicated security review.

**To productionize:** either require the destination account belong to the same client (mirroring
`convert()`), or route the sweep through `TransferServiceImpl` so it gets the same audit trail and
constraints a normal transfer does.

### A funded order settles as if every currency pair were a single-currency instrument

`ExecutionServiceImpl.settleFill()` runs whenever a filled order carries an `accountId`, debiting or
crediting `Account.balance` and maintaining a `Position` row. That's exactly correct for a
single-asset symbol like `TSLA` or a crypto pair like `BTC/USD` (cash in, one asset out — see
[CRYPTO.md](CRYPTO.md)). It's *not* correct for a genuine two-currency FX pair like `EUR/USD` — a
real FX trade debits one account in one currency and credits a different account in another, not
one account, one notional. Nothing in `OrderServiceImpl` actually stops an `accountId` from being
attached to a currency-pair order; the split between "settles for real" (stocks, crypto) and
"FX order, dealer-desk only" is a frontend/demo-data convention, not an enforced rule. Detail and
the productionization path: [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md).

### No order cancellation or expiry

An order that rests in the book — partially or fully unfilled — just stays `VALIDATED` or
`PARTIALLY_FILLED` indefinitely. There's no `DELETE`/cancel endpoint and no time-in-force concept.
See [TRADING_SYSTEM.md §1](TRADING_SYSTEM.md#1-order-lifecycle).

### `Payment` and `Transfer` don't carry a currency field

Both entities track an `amount` but no currency — a payment or transfer's currency is implicit
(the account's currency), never stored on the row itself. This shows up directly in the bank
statement: a `PAYMENT` or `TRANSFER_*` entry's `amount` is a bare signed number, not currency-
formatted, unlike a `DEPOSIT`/`WITHDRAWAL`/`LOAN_*` entry (which reads the currency off the linked
`Account`). See [`BankStatementEntry`](../src/main/java/com/dcbate/tradingplatform/statement/api/dto/BankStatementEntry.java)'s
javadoc.

### An FX order's exact settled amount isn't shown on the bank statement

`amount` always comes back `null` for a `FX_ORDER` entry. A partially-filled order's true settled
notional would have to be re-derived by summing its `Trade` rows rather than showing the order's
own quantity/price — a genuine derivation this doesn't attempt, to avoid a second, subtly different
source of truth for "how much actually settled." The order's status/quantity/price are still shown;
only the money-moved figure is omitted.

### The Kafka fallback queue is durable but single-instance

Chronicle Queue-backed, survives an app restart mid-outage — but each app instance only ever
drains its own local queue file, so it doesn't help once you run more than one instance. See
[OPERATIONS.md](OPERATIONS.md#known-operational-limitations-stated-directly).

### No production alerting destination

Five Prometheus alert rules fire and are visible in Prometheus's own UI/API, but nothing pages
anyone yet — no PagerDuty/Opsgenie/Alertmanager wired up. See
[OPERATIONS.md](OPERATIONS.md#known-operational-limitations-stated-directly).

### Two deployment targets, never reconciled

`deploy.yml` targets Cloud Run; `k8s/`/`helm/` target GKE. Both exist, neither has been chosen as
"the" path — a real architectural decision this project never forced itself to make.

## Deliberate simplifications

### An order with no `accountId` skips the funds/position check entirely

`OrderServiceImpl.submitOrder()`'s funding check only runs `if (request.accountId() != null)`.
Omit it and a sell goes through with no share-ownership check — the "dealer desk" pattern, and in
this demo it doubles as the only way new shares enter circulation at all (there's no IPO/
market-maker mechanism). Not a bug — a deliberately left-open gap standing in for a missing feature,
used on purpose to demonstrate fills. See [HOW_A_TRADE_FILLS.md](HOW_A_TRADE_FILLS.md).

### FX market prices are simulated

A randomized walk seeded per currency pair, not a real market data feed. Stated in the root
[README.md](../README.md#whats-real-and-what-simulated).

### Email/Slack notifications are logged, not sent

`NotificationServiceImpl` retries with backoff and DLQs on exhaustion — genuine retry logic — but
the actual "send" is a log line, not a real SendGrid/Slack API call. Stated in
[README.md](../README.md#whats-real-and-what-simulated).

### The "other bank" a payment clears against is a deterministic stand-in

`BankClearingClientImpl` fails above a $500,000 threshold on purpose, so both the success and
failure paths through the settlement saga are testable. Not a real risk control — see
[DESIGN_DECISIONS.md](DESIGN_DECISIONS.md).

### No collateral, credit checks, or variable/compounding interest on loans

Origination, simple daily interest accrual, and amortized repayment (interest first, then
principal) are real. Collateral, credit checks, variable/compounding rates, and default handling
beyond a status flag aren't modelled. See [ACCOUNTS.md §7](ACCOUNTS.md#7-loans--full-lifecycle).

### Cross-currency internal transfers are rejected, not auto-converted

`TransferServiceImpl` requires both accounts to share a currency (`CurrencyMismatchException` →
409 otherwise) — a client converts first (§9 of [ACCOUNTS.md](ACCOUNTS.md)), then transfers.
Auto-chaining the two isn't built.

### The JWT issuer is hand-rolled, not a managed identity provider

Real bcrypt hashing, real JWT issuance, real refresh-token rotation and server-side revocation on
logout — but no MFA, password reset, or email verification, and the dev profile grants every role
to every request for local testing convenience. See
[README.md §Security](../README.md#security) and
[DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) for the productionization path.

### Test coverage gaps, stated directly

The Claude API's success paths aren't unit-tested (the failure/fallback path is), `@PreAuthorize`
role enforcement itself isn't proven by controller unit tests (only the ownership-check layer on
top of it is, end-to-end, via `AccountSecurityIntegrationTest`), and two Testcontainers
end-to-end pipeline tests were removed after proving flaky in the Testcontainers networking
environment specifically, not in the application. See
[README.md §Test coverage](../README.md#test-coverage--stated-honestly).

## Not yet built at all

Tracked in the project's own task list, not hidden:

- Real SendGrid/Slack integrations (the retry/DLQ plumbing around them is real; the send isn't).
- A GitHub Actions deploy pipeline (written, never run against real cloud credentials).
- `DEPLOYMENT.md`/a dedicated deploy runbook (day-to-day operations are covered in
  [OPERATIONS.md](OPERATIONS.md); a from-scratch deploy walkthrough isn't).
