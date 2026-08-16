# Bate Banking, Explained

This doc explains the whole project twice: once in plain English for a non-technical reader, then
again for a software developer who wants the real technical shape of it. Both halves describe the
same system — pick whichever one matches how you want to read it, or read both.

Everything below reflects the code as it exists right now, not the original plan. Where something
was planned but never finished, I say so directly rather than describing the intention as if it
were the reality.

---

# Part 1 — In Plain English

## What this actually is

Bate Banking is a working pretend bank. Not a mockup with fake screenshots — an actual website
backed by an actual server and an actual database, where you can sign up, get a real login, open
an account, put money in it, take money out, send money to another person, pay someone at a
different bank, swap your money between currencies, borrow money and pay it back with interest,
and trade foreign currency and shares. Everything you do updates a real database row, exactly like
a real bank's systems would.

It exists to demonstrate how a bank's back-end actually works underneath the app you use — the
event-driven pipelines, the double-entry bookkeeping, the fraud checks, the security model that
stops one customer from seeing another customer's money. It is not a real bank, is not regulated,
and no real money ever moves — but the machinery that would move real money is built and running
for real.

## What you can actually do in it

**Sign up and log in.** A real account gets created, your password is properly encrypted (never
stored as plain text), and you get a real login session. When you sign up, a current account is
opened for you automatically.

**Open accounts.** You can hold several accounts — a current account, a savings account, an
account for trading foreign currency — in any of six currencies (pounds, dollars, euros, yen,
Swiss francs, Australian dollars).

**Deposit and withdraw.** Put money into any of your accounts, or take it out.

**Pay another customer at Bate Banking.** Instant — it's just one bank moving money between two of
its own customers, so there's nothing to wait on.

**Pay someone at a different bank.** This one is genuinely more involved, on purpose, because it's
what actually happens when money leaves a bank: the payment is checked for signs of fraud, then it
goes through a multi-step settlement process, and if any step fails partway through, everything
that already happened gets automatically undone so no money is created or destroyed by accident.

**Convert currency.** Move money from your pounds account into your dollars account (or any pair),
at a live-updating exchange rate.

**Take out a loan, and pay it back.** Borrow against one of your accounts, watch interest build up
over time, and repay it whenever you like — repayments clear the interest first, then the amount
you originally borrowed.

**Trade foreign currency and shares.** A proper trading desk: you place a buy or sell order, it
gets matched against other orders at the best available price, and you get a live-updating chart.

**Game Mode.** A separate, self-contained game bolted onto the same site: pick a difficulty, get a
pretend starting balance and a countdown timer, and try to grow your net worth to a target using
loans and trading, before the clock runs out or you go bankrupt. It uses its own fake market and
its own fake money — nothing in Game Mode ever touches your real account. You can even play it
without creating an account at all.

## What's real and what's pretend

Nearly everything is genuinely real: the database, the security, the double-entry bookkeeping, the
event pipeline that connects every part of the system, the fraud and risk checks, the loan
interest math, the order matching for trading. If you deposit £100 and then check your balance, a
real number changed in a real database — that part isn't simulated at all.

A handful of pieces are deliberately faked, because building the real version would mean
integrating with an actual outside company (a real bank, a real market data provider, a real email
service), which isn't something a demo project can do:

- **Emails and Slack alerts** aren't actually sent — they get written to a log file instead, as if
  they'd been sent. Everything around them (retrying a failed send, giving up after enough
  retries) is real; only the very last "send it" step is a stand-in.
- **Paying another bank** doesn't contact a real bank — it uses a simple made-up rule (payments
  under a certain size always succeed, larger ones are made to fail on purpose) so both the
  success path and the failure/undo path can actually be demonstrated.
- **Currency and share prices** move on their own using a randomised simulation, not a real live
  market feed.
- **Game Mode's entire market** is invented from scratch — its own price simulator, its own
  currencies and share prices, completely separate from the real trading desk above.

The login system is real — real passwords, real sessions, real security — but it's hand-built
rather than using an off-the-shelf identity provider like the ones big companies use (Google
Sign-In, Okta, etc.), so it's missing things like two-factor authentication or a password-reset
email flow.

## What isn't finished

A few things were planned but not built out:

- Sending real emails/Slack messages instead of just logging them (this is a small amount of extra
  work — the hard part, retrying failures, is already done).
- Actually deploying this to a real cloud server for the public to use (the deployment scripts are
  written but never actually run against a real cloud account).
- Connecting the trading desk's buy/sell fills to actually move money in your account balance —
  right now the trading desk and the account balances are two systems that don't quite touch yet.
- A password-reset flow, two-factor login, and a few other identity features a production bank
  would need.

---

# Part 2 — For a Software Developer

## Stack

Java 21, Spring Boot 4.1.0 on the backend; React 19 + TypeScript (Vite, Tailwind, Zustand, React
Query, React Router) on the frontend. Postgres 18 for storage, Kafka 4.3.1 as the event backbone,
Redis 8 for caching (price cache, JWT refresh-token state). Flyway for schema migrations (`V1`
through `V11`). Chronicle Queue for an off-heap trade journal. Jaeger + Prometheus + Grafana for
observability. Docker Compose for local dev; raw K8s manifests and a Helm chart exist and were
verified against a local `kind` cluster.

## The core architectural decision

Every service talks to every other service through Kafka — there are no direct synchronous
service-to-service calls anywhere in the domain logic. A payment being fraud-checked, settled, and
notified are three separate consumers reacting to events, not one method calling three others. This
is the thing the whole project is actually demonstrating: event-driven design, a saga pattern for
distributed operations that need to be undone if a downstream step fails, and a `KafkaEventPublisher`
with a fallback in-memory queue so a broker outage degrades gracefully instead of losing writes.

## Domain map

```
src/main/java/com/dcbate/tradingplatform/
├── domain/        entities + enums (Account, Payment, Transfer, Loan, Order, Trade, User, Game*)
├── security/      CallerPrincipal (ownership checks) + JwtIssuer
├── auth/          signup, login, refresh-token rotation
├── account/       open, deposit, withdraw, convert, balance summary
├── transfer/      same-bank transfers (no saga — same DB, atomic, no external hop)
├── payment/       fraud check → ledger → settlement saga → compensation on failure
├── loan/          product catalog, originate, repay, scheduled daily interest accrual
├── trading/       Order → Risk → MatchingEngine → Execution → PriceFeed (the FX/stock desk)
├── game/          Game Mode — entirely isolated: own tables, own market simulator, no Kafka
├── notification/  retry/DLQ delivery via @RetryableTopic
├── ai/            AnomalyDetector + ClaudeSummarizer + GameCoach — all three backed by Anthropic
│                  Claude, called via ai/mcp/ as a real MCP client of the separate bate-mcp-server
│                  project (../bate-mcp-server/), with rule-based fallback if unreachable or the
│                  server itself has no key configured
├── chronicle/     off-heap, memory-mapped, zero-GC trade journal
├── kafka/         KafkaEventPublisher (+ durable, restart-surviving fallback queue),
│                  KafkaListenerStartupRunner (consumers start once Kafka's reachable,
│                  rather than the app crashing at boot if it isn't), and every
│                  kafka.event.* record
├── config/        Kafka, Security, Trading, Chronicle, tracing config
├── exception/     domain exceptions + GlobalExceptionHandler (central HTTP status mapping)
└── actuator/      custom health indicator (matching-engine order-book depth)
```

`frontend/src/`: `pages/` (Login, Signup, Dashboard, Accounts, Transfer, Trading/FX Markets, Loans,
Settings, plus `GameLobbyPage`/`GamePlayPage`), `hooks/` (one React Query hook module per domain,
e.g. `useAccounts.ts`, `useGame.ts`), `store/` (Zustand — `authStore` for session state), `api/`
(axios client, cookie-based auth with automatic refresh-and-retry on a 401), `components/`,
`lib/` (formatting helpers, style constants).

## Security model

`CallerPrincipal` (a small record: `clientId`, `staff`) is derived from the validated JWT on every
request. Every client-facing service method takes it as a parameter and calls
`requireOwner(caller, resourceClientId)`, which throws `AccessDeniedException` (→ 403) unless the
caller owns the resource or holds a staff role. This is enforced end-to-end and proven by
`AccountSecurityIntegrationTest` — a real Testcontainers run against the non-dev filter chain with
hand-signed JWTs per client, asserting client A gets a 403 reading client B's account, not just
role-checked in isolation. The `dev` Spring profile (used by `docker-compose.yml` for local
convenience) grants every role to anonymous callers, so ownership checks are effectively bypassed
locally — never run `dev` outside local development.

Login/signup (`auth/`) issues real JWTs (HS256, hand-rolled `JwtIssuer`, not a managed IdP) as
HTTP-only, `SameSite=Strict` cookies. Refresh tokens rotate: redeeming one revokes it in the
`refresh_tokens` table before issuing a new pair, so replay fails outright. Logout revokes the
current refresh token server-side too — it used to only clear cookies client-side, leaving a stolen
token valid for up to 7 days after "logging out," which I found and fixed. `JwtIssuer` also refuses
to start at all if `jwt.secret` is still the known-insecure local-dev placeholder outside the
`dev`/`test` profiles, so a deployment that forgets to set a real `JWT_SECRET` fails loudly at boot
instead of silently signing forgeable tokens.

## What's real vs. simulated (the honest version)

This is intentionally documented in detail in [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) — the
summary:

| Piece | Status |
|---|---|
| Double-entry ledger, payment saga + compensation, ownership auth, Kafka pipeline + a durable (Chronicle Queue-backed, restart-surviving) fallback queue, risk/fraud engines, loan interest accrual, matching engine, tracing/metrics | **Real** |
| Claude anomaly enrichment / payment summaries / Game Mode debrief | **Real outbound API calls** when a key is configured; rule-based fallback otherwise — never blocks the underlying decision |
| Email/Slack delivery | **Logged stand-in** — `EmailSenderImpl`/`SlackSenderImpl`; the retry/DLQ machinery around them is real |
| Bank clearing (`BankClearingClient`) | **Deterministic stand-in** — payments under a configured threshold always clear, larger ones always fail, so both the settle and compensate paths are exercisable without a real correspondent-bank relationship |
| FX/stock price feed | **Simulated bounded random walk**, not a real market data subscription |
| FX order fills → account balances | **Gap, not a design choice** — the matching engine fills orders correctly, but fills never debit/credit `Account.balance`. Only Payments/Transfers/Deposits/Withdrawals/Conversion touch it today |
| Login/JWT | **Real**, but hand-rolled — no MFA, no password reset, no OAuth2 IdP |

## Game Mode — the one deliberately separate system

Game Mode (`game/` package, `game_sessions`/`game_positions`/`game_loans`/`game_trades` tables) is
a fully isolated economy: its own market simulator (`GameMarketServiceImpl`), no Kafka, no shared
tables with the real trading desk, playable without logging in (guest sessions via a
client-generated ID, `/v1/game/**` is `permitAll`). Full detail in
[GAME_MODE.md](GAME_MODE.md), including why the market uses regime-based price trends rather than
a pure random walk (a symmetric random walk has zero expected drift, which made every difficulty
unwinnable), how loan interest accrues per minute of game time rather than a real annualized
rate (a real APR is invisible over a 15–30 minute session), and the AI-written end-of-session
debrief (a third, independent use of the same Claude integration pattern described above).

## Testing and verification

210 backend tests (unit + one Testcontainers integration test for the security model), plus
frontend TypeScript strict-mode compilation and a production Vite build on every change. Beyond
automated tests, most features have been live-verified by hand against the running Docker Compose
stack — including distributed traces actually appearing in Jaeger, Prometheus actually scraping
151 metrics with a working Grafana dashboard, and three Gatling load-test scenarios actually run
(not just written) with real latency numbers in [PERFORMANCE_BASELINE.md](PERFORMANCE_BASELINE.md).
The Helm chart was verified against a real local `kind` cluster, not just written and assumed to
work.

## Known gaps, in priority order

1. Real SendGrid/Slack behind the existing `NotificationService` interface — retry/DLQ is already
   correct, only the sender implementations are stand-ins.
2. Wire FX/stock fills to actually move `Account.balance`.
3. A real cloud deployment (the GitHub Actions workflow exists but has never been run against real
   cloud credentials) and the `DEPLOYMENT.md` that would document it honestly once it has.
4. A real OAuth2/OIDC identity provider in front of the hand-rolled JWT issuer.
5. Credit checks, collateral, variable/compounding interest, and loan default handling beyond a
   status flag.

## Where to go deeper

- [README.md](../README.md) / [ARCHITECTURE.md](../ARCHITECTURE.md) — the maintained design
  references, with flow diagrams.
- [HANDOFF.md](../HANDOFF.md) — a point-in-time status snapshot (predates Game Mode and the
  frontend redesign) with a full endpoint inventory.
- [ACCOUNTS.md](ACCOUNTS.md), [PAYMENT_SYSTEM.md](PAYMENT_SYSTEM.md),
  [TRADING_SYSTEM.md](TRADING_SYSTEM.md), [GAME_MODE.md](GAME_MODE.md) — domain-by-domain detail.
- [DESIGN_DECISIONS.md](DESIGN_DECISIONS.md) — every real-vs-simulated call, with file paths and
  what productionizing each would take.
- [OBSERVABILITY_PROOF.md](OBSERVABILITY_PROOF.md), [PERFORMANCE_BASELINE.md](PERFORMANCE_BASELINE.md),
  [OPERATIONS.md](OPERATIONS.md), [KAFKA_SETUP.md](KAFKA_SETUP.md) — the operational layer.
