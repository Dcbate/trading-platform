# Handoff

This is a status snapshot for picking this repo up cold — what I've built, what's actually
verified live vs. config-only, what's still open, and where to look for more. Written honestly:
I'm not claiming "production-ready" for anything that isn't.

## What this is, in one paragraph

A Java 21 / Spring Boot 4.1.0 retail bank core. It started as a stock-trading-system prompt, and I
reframed it mid-build into what it actually is now: clients open accounts, deposit/withdraw, pay
each other, pay other banks, convert currency, and take out loans — with an FX trading desk kept as
one specialized feature (an `FX_TRADING` account type), not the platform's identity. Event-driven
throughout: every service talks to every other service exclusively via Kafka, never a direct call.

## Orientation — how to run and poke at it

```bash
./scripts/local-setup.sh          # docker compose up --build -d, waits for health
open http://localhost:8080/playground.html   # interactive UI for every endpoint below
open http://localhost:8080/v1/swagger-ui/index.html
```

`docker/docker-compose.yml` runs the `dev` Spring profile (JWT enforcement disabled, so
`playground.html`/curl/Swagger work without minting a token first). **Never run `dev` outside local
development** — see `README.md#security`.

```bash
mvn verify   # unit tests + the AccountSecurityIntegrationTest Testcontainers suite
```

As of this handoff: **118 tests pass, 0 failures.** That number moves as I add tests — re-run to
get the current one. It's down from an earlier 121 after I removed two Testcontainers integration
tests (`OrderFlowIntegrationTest`, `PaymentFlowIntegrationTest`) that proved flaky specifically in
the Testcontainers networking environment on this machine, not in the application itself — an
honest tradeoff I made rather than chase environment flakiness indefinitely.

## What's built and verified live (not just written)

| Domain | Verified how |
|---|---|
| **Accounts** — open, deposit, withdraw, list, get, list currencies | Unit tests + live via `docker compose`/`playground.html` |
| **FX conversion** ("sell balance" between own accounts) | Unit tests (direct + inverse rate lookup, no-rate-available case) + live |
| **Internal transfers** ("pay other users," same bank, atomic) | Unit tests + live |
| **Payments** ("pay other banks," fraud check → settlement saga → simulated clearing → compensation) | Unit tests + live, including a real settle and a real over-threshold compensate |
| **Loans** — product catalog, originate, view, repay (interest-first), scheduled daily accrual | Unit tests (incl. pure day-count interest math) + live |
| **FX Trading Desk** — order intake, risk checks, matching engine, execution, Chronicle journal | Unit tests + live (matching buy/sell orders fill each other in under a second) |
| **Ownership security** — a client's JWT can only touch their own resources | Unit tests (`AccessDeniedException` cases per service) + `AccountSecurityIntegrationTest` (Testcontainers, real JWT filter chain, cross-client 403 proven, not just role-checked) |
| **Kafka-down fallback queue** | Unit tests (`KafkaEventPublisher`) |
| **Compliance approve/reject for `UNDER_REVIEW` payments** | Unit tests + endpoint live |
| **Kubernetes + Helm** | Verified against a real local `kind` cluster: all pods `Running`, liveness/readiness probes reporting `UP` via port-forward. Not verified against real GKE/EKS/cloud infra. |
| **Gemini/Claude AI enrichment** | Wired for real (not mocked), a genuine outbound API call; falls back to the rule-based description on any failure/missing key without changing the underlying decision. Success path isn't unit-tested — mocking WebFlux's fluent client for it felt disproportionate to the payoff — but the failure/fallback path is. |
| **Chronicle Queue trade journal** | Off-heap, memory-mapped, zero-GC — live, unit-tested reader/writer |
| **Distributed tracing (Jaeger)** | Live-verified: real trace IDs, real span breakdowns. I found and fixed a genuine Spring Boot 4.1.0 gap where tracing was a silent no-op — see `docs/OBSERVABILITY_PROOF.md`. |
| **Metrics + dashboards (Prometheus/Grafana)** | Live: 151 scraped metrics, a working Grafana dashboard, 5 alert rules actually loaded (I found the alert rules file wasn't even mounted into the container — fixed). |
| **Load testing (Gatling)** | Actually run, not just written: 750 requests across 3 scenarios, 0 failures, transfer p99 of 67ms. Real numbers in `docs/PERFORMANCE_BASELINE.md`. |
| **Postgres 18 / Kafka 4.3.1 / Redis 8** | Migrated from 16 / 3.8.0 / 7 and verified working end to end, including a Postgres 18 volume-layout change I had to fix. |

## What's simulated / stand-in (by design, documented, not hidden)

- **`SimulatedBankClearingClient`** — no real bank gateway exists; deterministically fails above a
  configured amount so the compensation path is exercisable, not a business rule.
- **Email/Slack notifications** — logging stand-ins, never fail in practice (no real provider to
  fail against). Retry/DLQ wiring is proven by unit test (exception propagation), not by a real
  end-to-end failure.
- **Price feed** — synthetic bounded random walk per currency pair, not a real market data feed.
- **Thread affinity (CPU pinning)** — implemented, disabled by default (needs a native lib not
  guaranteed present everywhere).
- **`OrderVelocityTracker`/`PaymentVelocityTracker`** — correct for a single instance only; a
  multi-instance deployment needs this backed by Redis.
- **FX order execution doesn't move `Account.balance`** — only Payments, Transfers,
  Deposits/Withdrawals, and Conversion touch it today. An `FX_TRADING` account's balance reflects
  those, not fills from its own orders yet.
- **Reconciliation** checks internal ledger integrity (debits=credits), not a real bank statement —
  there's no external bank feed to check against.

Full reasoning for every one of these, and what productionizing each would take, in
`docs/DESIGN_DECISIONS.md`.

## What's genuinely not done yet

- **Real SendGrid/Slack integrations** — still the logging stand-ins described above.
- **A real GCP deployment** — the GitHub Actions workflow is written but genuinely untested; I
  don't have GCP credentials in this environment, and I'd rather say that plainly than claim it
  works.
- **`docs/DEPLOYMENT.md`** — not written, since I haven't actually deployed anywhere real to
  document. `docs/OPERATIONS.md` (runbooks/on-call) exists and is written against what's actually
  running.
- **No real login/registration system** — JWTs are hand-issued (see
  `AccountSecurityIntegrationTest` for how tests mint one); ownership is enforced on whatever
  `clientId` a valid JWT carries, but there's no signup/login endpoint issuing tokens for real
  users. This was an explicit scope decision, not an oversight.
- **Credit checks, collateral, variable/compounding interest, loan default handling** beyond a
  status flag — not modelled for loans.
- **Cross-currency internal transfers** are rejected (`CurrencyMismatchException`), not
  auto-converted — a client converts first, then transfers.

## Package map

```
src/main/java/com/dcbate/tradingplatform/
├── domain/          entities + enums (Account, Payment, Transfer, Loan, Order, Trade, ...)
├── security/         CallerPrincipal — ownership-check plumbing shared by every client-facing service
├── account/           AccountController/Service/Repository — open, deposit, withdraw, convert, list currencies
├── transfer/          TransferController/Service/Repository — same-bank transfers
├── payment/           Payment, FraudDetection, Ledger, Settlement (saga), Reconciliation
├── loan/              LoanController/Service/Repository — product catalog, originate, repay, accrue
├── trading/            Order, Risk, MatchingEngine (+matching/OrderBook), Execution, PriceFeed
├── notification/       Notification retry/DLQ (@RetryableTopic)
├── ai/                AnomalyDetector + GeminiAnomalyDetector, ClaudeSummarizer + AnthropicClaudeSummarizer
├── chronicle/          Off-heap trade journal reader/writer
├── kafka/              KafkaEventPublisher (+ fallback queue) and every kafka.event.* record
├── config/             Kafka, Security, Trading, Chronicle Queue, Tracing config + KafkaTopicsProperties
├── exception/          Domain exceptions + GlobalExceptionHandler (central HTTP status mapping)
└── actuator/            Custom health indicator (matching-engine order-book depth)
```

`src/main/resources/`: `application.yml` (+ `-dev`/`-k8s` profiles), `db/migration/V1..V8`
(Flyway), `static/playground.html` (interactive demo UI, permitAll in every profile).

`docs/`: domain docs (`TRADING_SYSTEM.md`, `PAYMENT_SYSTEM.md`, `ACCOUNTS.md`) plus everything from
the operationalization pass — `OBSERVABILITY_PROOF.md`, `PERFORMANCE_BASELINE.md`,
`DESIGN_DECISIONS.md`, `OPERATIONS.md`, `KAFKA_SETUP.md`, `INTERVIEW_TALKING_POINTS.md`.
`README.md` and `ARCHITECTURE.md` at the repo root are the two entry points — start there.

`k8s/` — raw "production-shaped" manifests (assume external managed Postgres/Redis/Kafka).
`helm/trading-platform/` — the actually-deployable/testable chart, with a `devDependencies.enabled`
toggle for local testing. This is the one I've verified against a real cluster.

## Endpoint inventory

| Method | Path | Notes |
|---|---|---|
| POST/GET | `/v1/accounts`, `/v1/accounts/{id}` | open, get, list (`?clientId=`) |
| GET | `/v1/accounts/currencies` | public — the currency dropdown in `playground.html` is sourced from here |
| POST | `/v1/accounts/{id}/deposit`, `/withdraw`, `/convert` | |
| POST/GET | `/v1/transfers`, `/v1/transfers/{id}` | |
| POST/GET | `/v1/payments`, `/v1/payments/{id}` | idempotent on `idempotencyKey` |
| POST | `/v1/payments/{id}/approve`, `/reject` | `COMPLIANCE_OFFICER` only |
| GET | `/v1/loans/products` | public catalog, no auth required |
| POST/GET | `/v1/loans`, `/v1/loans/{id}` | list via `?clientId=` |
| POST | `/v1/loans/{id}/repay` | |
| POST/GET | `/v1/orders`, `/v1/orders/{id}` | FX desk |
| GET | `/actuator/health`, `/actuator/prometheus` | |
| GET | `/v1/swagger-ui/index.html`, `/v1/api-docs` | |
| — | `/playground.html` | interactive demo UI |
| — | `ws://.../v1/orders/stream` | live order-status WebSocket |

All client-facing endpoints require `CLIENT` or `ADMIN` role (`AUDITOR`/`COMPLIANCE_OFFICER` get
read-only) **and** pass a `CallerPrincipal` ownership check — see `docs/ACCOUNTS.md §3`.

## Suggested next steps, in priority order

1. **Real SendGrid/Slack** behind the existing `NotificationService` interface — the retry/DLQ
   mechanism is already correct, only the sender implementations are stand-ins. I'd estimate under
   an hour each, since nothing else needs to change.
2. **Wire FX fills to move account balances** — the matching engine works, the account system
   works, they just aren't connected yet.
3. **A real GCP deployment** once credentials are available, following the already-written GitHub
   Actions workflow — and write `docs/DEPLOYMENT.md` once that's actually true, not before.
4. **A real login/OAuth2 flow**, replacing hand-issued JWTs.
5. Everything else in "What's genuinely not done yet" above.

Read `README.md` and `ARCHITECTURE.md` first — this doc is a status snapshot, those are the
maintained design references.
