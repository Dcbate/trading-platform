# Accounts, Transfers, Conversion & Loans

This is the deep dive into the retail banking domain: account types, deposits/withdrawals, internal
transfers, FX conversion, loans, and the ownership-based security model that gates all of it. For
the broader system design, see [ARCHITECTURE.md](../ARCHITECTURE.md); for cross-bank payments, see
[PAYMENT_SYSTEM.md](PAYMENT_SYSTEM.md); for the FX trading desk itself, see
[TRADING_SYSTEM.md](TRADING_SYSTEM.md).

## 1. Why this exists

I built the platform around a client's bank **account** as the center of gravity — everything else
(payments, transfers, FX orders, loans) is just a way money moves in or out of one.
`Account.balance` is the single source of truth for "how much money is here"; every feature below
either reads it (ownership checks) or mutates it inside one `@Transactional` method, never both
non-atomically. I wanted that property to be structural, not something I had to remember to
preserve every time I touched money.

## 2. Account types

`AccountType`: `CHECKING`, `SAVINGS`, `FX_TRADING`. All three are the same entity shape — type is a
label, not a different schema — because I decided "one balance, one currency, many uses" was the
right scope, not type-specific behavior. No savings interest, no withdrawal limits by type; those
would be realistic follow-ons, I just didn't build them. `FX_TRADING` is what funds/receives orders
on the FX desk ([TRADING_SYSTEM.md](TRADING_SYSTEM.md)) — a client can hold one of these alongside
an ordinary `CHECKING` account, which is what "FX trading is an account type" means in practice.

A client can open as many accounts, of any mix of types and currencies, as they want:
`POST /v1/accounts {clientId, accountType, currency, openingBalance}`. List them with
`GET /v1/accounts?clientId=`.

`GET /v1/accounts/balances?clientId=` returns the total across a client's **active** accounts,
grouped by currency — `{clientId, activeAccountCount, balances: [{currency, totalBalance, accountCount}]}`.
Closed and frozen accounts are excluded (a closed account's balance is always zero anyway; a frozen
one isn't spendable), and different currencies are never summed into one number — that would be
meaningless, not just imprecise. This is a read-only aggregate over the same rows `listAccounts`
already returns; no new table, no new Kafka topic.

## 3. Ownership security

Every account/payment/transfer/loan endpoint enforces that **a client can only see or act on their
own resources**. I want to be precise about what that means, because "role-based security" alone
doesn't get you there.

- `CallerPrincipal` ([security/CallerPrincipal.java](../src/main/java/com/dcbate/tradingplatform/security/CallerPrincipal.java))
  is resolved once per request from the caller's JWT: `clientId` is the `sub` claim, `staff` is
  true for `ADMIN`/`AUDITOR`/`COMPLIANCE_OFFICER`/`TRADER` roles.
- Controllers resolve it and pass it explicitly into services — I deliberately didn't read it from
  a static `SecurityContextHolder` inside a service, because I wanted ownership checks to stay
  trivially unit-testable: construct a fake `CallerPrincipal`, no security-context mocking needed.
- `CallerPrincipal.requireOwner(resourceClientId)` throws `AccessDeniedException` (mapped to 403)
  unless the caller owns the resource or is staff. Staff can act across clients — an `ADMIN` can
  open an account for any client, a `COMPLIANCE_OFFICER` can view any payment — because that
  mirrors how real bank operations tooling actually works.
- The `dev` profile grants the anonymous principal every role including `CLIENT`, so local `curl`
  testing works without minting a JWT. I didn't want to just take my own word that the real check
  works, though — `AccountSecurityIntegrationTest` proves the ownership check end-to-end through
  the *real* (not dev) JWT filter chain, and a client's token genuinely gets a 403 reading another
  client's account.

## 4. Deposits & withdrawals

`POST /v1/accounts/{accountId}/deposit` and `/withdraw`, body `{amount}`. Ownership-checked,
requires `status == ACTIVE`; withdrawal additionally requires sufficient balance
(`InsufficientFundsException` → 409). Both mutate `Account.balance` directly inside one transaction,
persist an `AccountActivity` row, and publish an `AccountActivityEvent` to `account-activity` — the
same event-sourced audit trail pattern I use for payments, just for money that didn't come from a
`Payment` row. Conversion (§6) and closure (§3) write the same `AccountActivity` table with their
own `type`. This row is what makes deposits/withdrawals/conversions queryable per client, not just
a fire-and-forget Kafka event — see §10.

## 5. Internal transfers — "pay other users money"

`POST /v1/transfers {fromAccountId, toAccountId, amount}` moves money between two clients' accounts
**at this bank**. I made this deliberately its own domain
([`TransferServiceImpl`](../src/main/java/com/dcbate/tradingplatform/transfer/service/TransferServiceImpl.java)),
not bolted onto `PaymentService`, because the mechanics are genuinely different from a cross-bank
payment, and I didn't want to force one abstraction over two problems that don't actually match:

| | Internal transfer | External payment (see PAYMENT_SYSTEM.md) |
|---|---|---|
| Counterparty | Another client at this bank | Another bank, another country |
| Consistency | One atomic DB transaction (debit + credit + write the `Transfer` row) | A saga: reserve → ledger → external clear → compensate |
| Why | Both balances live in my own database — nothing external can fail after I commit | Bank clearing is an external system that can fail *after* my data is already written, so a compensating step is required |
| Currency | Must match (`CurrencyMismatchException` → 409 otherwise — convert first, §6) | N/A — cross-border payments carry an amount + country, not a currency conversion |

Both accounts must be `ACTIVE`; I only check ownership on `fromAccountId` — you can transfer *to* an
account you don't own, that's the point of paying someone else. A `TransferEvent` publishes to the
`transfers` topic.

## 6. Currency conversion — "sell balance"

`POST /v1/accounts/{fromAccountId}/convert {toAccountId, amount}` moves money between **two of the
caller's own accounts** in different currencies, at the live FX rate. I require ownership on *both*
accounts here, since you're not paying anyone, just rebalancing your own money.

I reused the FX trading desk's price feed (`PriceFeedService.currentPrice(currencyPair)`,
[TRADING_SYSTEM.md](TRADING_SYSTEM.md)) rather than the order-matching engine — a conversion is a
direct rate lookup, not an order waiting to be crossed against another client's order. It checks
the direct pair (e.g. `EUR/USD`) and, if that's not cached, the inverse (`USD/EUR`, using `1/rate`)
before failing with `RateUnavailableException` (409) — a realistic constraint I wanted to keep: the
FX desk needs an active price stream for a conversion to be possible at all, the same way a real
desk can't quote a rate it doesn't have. An `AccountActivityEvent` (type `CONVERSION`, carrying both
accounts and the rate used) publishes to `account-activity`.

**Explicitly out of scope, and I'd rather say so than let you find out the hard way**: FX order
execution — the matching engine itself — does not move `Account.balance`. Only payments, transfers,
deposits/withdrawals, and conversion do. Wiring the order book into real settlement is a follow-on
I haven't gotten to.

## 7. Loans

Full lifecycle in [`LoanServiceImpl`](../src/main/java/com/dcbate/tradingplatform/loan/service/LoanServiceImpl.java):

```
ACTIVE → PAID_OFF   (outstandingPrincipal AND accruedInterest both reach zero)
```

- **Product catalog**: `GET /v1/loans/products` (no auth required — it's a public rate sheet, not a
  client resource) lists my fixed catalog
  ([`LoanProductType`](../src/main/java/com/dcbate/tradingplatform/domain/LoanProductType.java)):
  `PERSONAL_SHORT` (1yr, 9.99%), `PERSONAL_LONG` (3yr, 7.49%), `AUTO` (5yr, 5.25%), `STUDENT` (10yr,
  4.25%), `MORTGAGE` (30yr, 3.75%). The rate and term are properties of the product, not something
  the caller types in — I originally let free-text interest rates through, and it felt wrong the
  moment I built it, so I replaced it with a fixed catalog, the same way a real bank prices a loan
  by product rather than letting you name your own rate.
- **Originate**: `POST /v1/loans {clientId, accountId, principal, productType}` — ownership-checked
  on `accountId`, looks up the rate and term from `productType` and snapshots both onto the `Loan`
  row (so a later catalog change never retroactively alters an existing loan), credits the account
  by `principal` (loan proceeds land in a real account, the same mechanic as a deposit), publishes
  `LoanEvent` (`ORIGINATED`) to `loans`.
- **View**: `GET /v1/loans/{id}` returns `productType`, `principal`, `outstandingPrincipal`,
  `interestRateAnnualPercent`, `termMonths`, and `accruedInterest` — "see the loan amount and
  interest."
- **Interest accrual**: `LoanInterestScheduler` (cron `loan.interest.accrual-cron`, default 03:00
  daily) calls `LoanServiceImpl.accrueInterest()` for every `ACTIVE` loan — simple, non-compounding
  daily interest: `outstandingPrincipal × (rate/100) × (daysSinceLastAccrual/365)`, added to
  `accruedInterest`. I kept the day-count math as a pure package-private method
  (`calculateAccrual`), unit-tested directly with explicit day counts rather than by manipulating
  wall-clock time in a test — I've been burned by clock-mocking tests before and didn't want to
  repeat it.
- **Repay**: `POST /v1/loans/{id}/repay {amount}` — debits the linked account (insufficient funds →
  the same exception payments/transfers use), applies the payment to `accruedInterest` **first**,
  then `outstandingPrincipal` — real amortization order. If the requested amount exceeds what's
  actually owed, I only take what's owed, never more. Flips to `PAID_OFF` when both hit zero;
  repaying a `PAID_OFF` loan throws `LoanNotActiveException` (409).

**Explicitly out of scope**: credit checks, collateral, variable/compounding interest, and default
handling beyond a status flag. I didn't model any of that.

## 8. Kafka topics

Three additions alongside the trading/payment topics, same `NewTopic`-bean/`KafkaEventPublisher`
pattern I used everywhere else in the platform (including the Kafka-down fallback queue — see
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

## 10. Bank statement — a unified history

`GET /v1/statement?clientId=` (`BankStatementController` → `BankStatementServiceImpl`,
ownership-checked the same way as everything else) merges five sources into one
newest-first feed: FX orders, payments, transfers, account activity, and loan activity.

Before this existed, deposits/withdrawals/conversions/closures and loan repayments were only ever
published to Kafka for audit — genuinely useful history a client would expect to see was
unqueryable, only the *current* balance/loan state was. Two new tables close that: `account_activity`
(deposit/withdraw/convert/close, written by `AccountServiceImpl` alongside every `AccountActivityEvent`
publish) and `loan_activity` (origination/repayment, written by `LoanServiceImpl` alongside every
`LoanEvent` publish). Each is the persisted twin of an event that was already being published — same
data, now also a row, so nothing about the Kafka contract changed.

One deliberate simplification: an FX order's `amount` always comes back `null` on the statement.
`ExecutionServiceImpl` settles fills against `Account.balance` at the individual-`Trade` level, and
a partially-filled order's exact settled notional would have to be re-derived by summing `Trade`
rows rather than showing the genuine, queryable `Order` row I already have. Flagged, not hidden —
see [KNOWN_GAPS.md](KNOWN_GAPS.md).
