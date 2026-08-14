# Accounts, Transfers, Conversion & Loans

Deep dive into the retail banking domain: account types, deposits/withdrawals, internal
transfers, FX conversion, loans, and the ownership-based security model that gates all of it. For
the broader system design, see [ARCHITECTURE.md](../ARCHITECTURE.md); for cross-bank payments,
see [PAYMENT_SYSTEM.md](PAYMENT_SYSTEM.md); for the FX trading desk itself, see
[TRADING_SYSTEM.md](TRADING_SYSTEM.md).

## 1. Why this exists

The platform's core idea is a client's bank **account** — everything else (payments, transfers,
FX orders, loans) is a way money moves in or out of one. `Account.balance` is the single source of
truth for "how much money is here"; every feature below either reads it (ownership checks) or
mutates it inside one `@Transactional` method, never both non-atomically.

## 2. Account types

`AccountType`: `CHECKING`, `SAVINGS`, `FX_TRADING`. All three are the same entity shape — type is
a label, not a different schema — because Phase 1's scope is "one balance, one currency, many
uses," not type-specific behavior (no savings interest, no withdrawal limits by type; those would
be realistic follow-ons, not built here). `FX_TRADING` is what funds/receives orders on the FX
desk ([TRADING_SYSTEM.md](TRADING_SYSTEM.md)) — a client can hold one of these alongside an
ordinary `CHECKING` account, which is what "FX trading is an account type" means in practice.

A client can open as many accounts, of any mix of types and currencies, as they want:
`POST /v1/accounts {clientId, accountType, currency, openingBalance}`.

## 3. Ownership security

Every account/payment/transfer/loan endpoint enforces that **a client can only see or act on their
own resources** — this is the "strong security" requirement, and it's real authorization, not just
role gating.

- `CallerPrincipal` ([security/CallerPrincipal.java](../src/main/java/com/dcbate/tradingplatform/security/CallerPrincipal.java))
  is resolved once per request from the caller's JWT: `clientId` is the `sub` claim, `staff` is
  true for `ADMIN`/`AUDITOR`/`COMPLIANCE_OFFICER`/`TRADER` roles.
- Controllers resolve it and pass it explicitly into services — not a static
  `SecurityContextHolder` read inside a service — so ownership checks stay trivially unit-testable
  (construct a fake `CallerPrincipal`, no security-context mocking needed).
- `CallerPrincipal.requireOwner(resourceClientId)` throws `AccessDeniedException` (mapped to 403)
  unless the caller owns the resource or is staff. Staff can act across clients (an `ADMIN` can
  open an account for any client, a `COMPLIANCE_OFFICER` can view any payment) — this mirrors real
  bank operations tooling.
- The `dev` profile grants the anonymous principal every role including `CLIENT` (same convenience
  pattern as `@PreAuthorize` already used), so local `curl` testing works without minting a JWT.
  `AccountSecurityIntegrationTest` proves the ownership check end-to-end through the *real* (not
  dev) JWT filter chain — a client's token gets a genuine 403 reading another client's account.

## 4. Deposits & withdrawals

`POST /v1/accounts/{accountId}/deposit` and `/withdraw`, body `{amount}`. Ownership-checked,
requires `status == ACTIVE`; withdrawal additionally requires sufficient balance
(`InsufficientFundsException` → 409). Both mutate `Account.balance` directly inside one
transaction and publish an `AccountActivityEvent` to `account-activity` — the same event-sourced
audit trail pattern as payments, just for money that didn't come from a `Payment` row.

## 5. Internal transfers — "pay other users money"

`POST /v1/transfers {fromAccountId, toAccountId, amount}` moves money between two clients' accounts
**at this bank**. This is deliberately its own domain
([`TransferServiceImpl`](../src/main/java/com/dcbate/tradingplatform/transfer/service/TransferServiceImpl.java)),
not bolted onto `PaymentService`, because the mechanics are genuinely different from a cross-bank
payment:

| | Internal transfer | External payment (see PAYMENT_SYSTEM.md) |
|---|---|---|
| Counterparty | Another client at this bank | Another bank, another country |
| Consistency | One atomic DB transaction (debit + credit + write the `Transfer` row) | A saga: reserve → ledger → external clear → compensate |
| Why | Both balances live in our own database — nothing external can fail after we commit | Bank clearing is an external system that can fail *after* our data is already written, so a compensating step is required |
| Currency | Must match (`CurrencyMismatchException` → 409 otherwise — convert first, §6) | N/A — cross-border payments carry an amount + country, not a currency conversion |

Both accounts must be `ACTIVE`; ownership is checked on `fromAccountId` only (you can transfer *to*
an account you don't own — that's the point of paying someone else). A `TransferEvent` publishes
to the `transfers` topic.

## 6. Currency conversion — "sell balance"

`POST /v1/accounts/{fromAccountId}/convert {toAccountId, amount}` moves money between **two of the
caller's own accounts** in different currencies, at the live FX rate — ownership is required on
*both* accounts, since you're not paying anyone, just rebalancing your own money.

This reuses the FX trading desk's price feed
(`PriceFeedService.currentPrice(currencyPair)`,
[TRADING_SYSTEM.md](TRADING_SYSTEM.md)) rather than the order-matching engine: a conversion is a
direct rate lookup, not an order that waits to be crossed against another client's order. It checks
the direct pair (e.g. `EUR/USD`) and, if that's not cached, the inverse (`USD/EUR`, using `1/rate`)
before failing with `RateUnavailableException` (409) — a realistic constraint: the FX desk needs an
active price stream (`PriceFeedScheduler`'s ticks) for a conversion to be possible at all. An
`AccountActivityEvent` (type `CONVERSION`, carrying both accounts and the rate used) publishes to
`account-activity`.

**Explicitly out of scope**: FX order execution (the matching engine itself) does not move
`Account.balance` — only Payments, Transfers, Deposits/Withdrawals, and Conversion do. Wiring the
order book into real settlement is a further follow-on.

## 7. Loans

Full lifecycle in [`LoanServiceImpl`](../src/main/java/com/dcbate/tradingplatform/loan/service/LoanServiceImpl.java):

```
ACTIVE → PAID_OFF   (outstandingPrincipal AND accruedInterest both reach zero)
```

- **Originate**: `POST /v1/loans {clientId, accountId, principal, interestRateAnnualPercent}` —
  ownership-checked on `accountId`, credits the account by `principal` (loan proceeds land in a
  real account, the same mechanic as a deposit), creates the `Loan` row, publishes `LoanEvent`
  (`ORIGINATED`) to `loans`.
- **View**: `GET /v1/loans/{id}` returns `principal`, `outstandingPrincipal`,
  `interestRateAnnualPercent`, and `accruedInterest` — "see the loan amount and interest."
- **Interest accrual**: `LoanInterestScheduler` (cron `loan.interest.accrual-cron`, default 03:00
  daily) calls `LoanServiceImpl.accrueInterest()` for every `ACTIVE` loan — simple (non-compounding)
  daily interest: `outstandingPrincipal × (rate/100) × (daysSinceLastAccrual/365)`, added to
  `accruedInterest`. The day-count math is a pure package-private method
  (`calculateAccrual`), unit-tested directly with explicit day counts rather than by manipulating
  wall-clock time.
- **Repay**: `POST /v1/loans/{id}/repay {amount}` — debits the linked account (insufficient funds
  → the same exception payments/transfers use), applies the payment to `accruedInterest` **first**,
  then `outstandingPrincipal` — real amortization order. If the requested amount exceeds what's
  actually owed, only what's owed is taken from the account, never more (no silent overcharge).
  Flips to `PAID_OFF` when both hit zero; repaying a `PAID_OFF` loan throws `LoanNotActiveException`
  (409).

**Explicitly out of scope**: credit checks, collateral, variable/compounding interest, and default
handling beyond a status flag are not modelled.

## 8. Kafka topics

Three additions alongside the trading/payment topics, same `NewTopic`-bean/`KafkaEventPublisher`
pattern as everywhere else in the platform (including the Kafka-down fallback queue — see
[ARCHITECTURE.md](../ARCHITECTURE.md#5-reliability-pattern-retry--dlq)):

| Topic | Published by | Carries |
|---|---|---|
| `account-activity` | `AccountServiceImpl` | Deposits, withdrawals, conversions |
| `transfers` | `TransferServiceImpl` | Completed internal transfers |
| `loans` | `LoanServiceImpl` | Loan origination and repayments |

## 9. Failure scenarios

| Failure | Behavior |
|---|---|
| Withdraw/transfer/loan-repay for more than the balance | `InsufficientFundsException` → 409, nothing mutated |
| Deposit/withdraw/transfer/convert/loan-originate on a `FROZEN`/`CLOSED` account | `AccountNotActiveException` → 409 |
| Transfer between different-currency accounts | `CurrencyMismatchException` → 409 — convert first |
| Convert with no cached FX rate for the pair (or its inverse) | `RateUnavailableException` → 409 |
| Repay a `PAID_OFF` loan | `LoanNotActiveException` → 409 |
| A client's token used against another client's account/payment/transfer/loan | `AccessDeniedException` → 403, proven end-to-end by `AccountSecurityIntegrationTest` |
