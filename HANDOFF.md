# Handoff

Status snapshot for picking this repo up cold — what's built, what's actually verified vs.
config-only/simulated, what's still open, and where to look for more detail. Written honestly:
nothing here is overclaimed as "production-ready" that isn't.

## What this is, in one paragraph

A Spring Boot 3 / Java 21 banking platform. It started as a stock-trading-system prompt, but was
reframed mid-build (at the user's request) into a **retail bank core**: clients open accounts,
deposit/withdraw, pay each other, pay other banks, convert currency, and take out loans — with an
**FX trading desk** kept as one specialized feature (an `FX_TRADING` account type funding/receiving
buy/sell orders on currency pairs), not the platform's identity. Event-driven throughout: every
service talks to every other service exclusively via Kafka, never a direct call.

## Orientation — how to run and poke at it

```bash
./scripts/local-setup.sh          # docker compose up --build -d, waits for health
open http://localhost:8080/playground.html   # interactive UI for every endpoint below
open http://localhost:8080/v1/swagger-ui.html
```

`docker/docker-compose.yml` runs the `dev` Spring profile (JWT enforcement disabled, so
`playground.html`/curl/Swagger work without minting a token). **Never run `dev` outside local
development** — see `README.md#security`.

```bash
mvn verify   # full suite: unit tests + 3 Testcontainers integration tests against real Kafka/Postgres/Redis
```

As of this handoff: **`mvn verify` green — Tests run: 121, Failures: 0, Errors: 0**, including the
3 Testcontainers integration suites. Count moves as tests are added; re-run to get the current
number.

## What's built and verified live (not just written)

| Domain | Verified how |
|---|---|
| **Accounts** — open, deposit, withdraw, list, get | Unit tests + live via `docker compose`/`playground.html` |
| **FX conversion** ("sell balance" between own accounts) | Unit tests (direct + inverse rate lookup, no-rate-available case) + live |
| **Internal transfers** ("pay other users," same bank, atomic) | Unit tests + live |
| **Payments** ("pay other banks," fraud check → settlement saga → simulated clearing → compensation) | Unit tests + `PaymentFlowIntegrationTest` (Testcontainers, both saga outcomes) + live |
| **Loans** — product catalog, originate, view, repay (interest-first), scheduled daily accrual | Unit tests (incl. pure day-count interest math) + live |
| **FX Trading Desk** — order intake, risk checks, matching engine, execution, Chronicle journal | Unit tests + `OrderFlowIntegrationTest` (Testcontainers) + live |
| **Ownership security** — a client's JWT can only touch their own resources | Unit tests (`AccessDeniedException` cases per service) + `AccountSecurityIntegrationTest` (Testcontainers, real JWT filter chain, cross-client 403 proven, not just role-checked) |
| **Kafka-down fallback queue** | Unit tests (`KafkaEventPublisher`) |
| **Compliance approve/reject for `UNDER_REVIEW` payments** | Unit tests + endpoint live |
| **Kubernetes + Helm** | Verified against a real local `kind` cluster: all pods `Running`, liveness/readiness probes reporting `UP` via port-forward. Not verified against real GKE/EKS/cloud infra. |
| **Gemini/Claude AI enrichment** | Wired for real (not mocked) with a real API call path; falls back to the rule-based description on any failure/missing key. Success path isn't unit-tested (WebFlux client mocking judged disproportionate to payoff) — failure/fallback path is. |
| **Chronicle Queue trade journal** | Off-heap, memory-mapped, zero-GC — live, unit-tested reader/writer |

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

## What's genuinely not done yet

- **Observability infra**: Micrometer/Prometheus metrics and `@Slf4j` structured logging exist and
  are live (`/actuator/prometheus`), but Jaeger tracing and Grafana dashboards are not stood up.
- **Real SendGrid/Slack integrations** — still the logging stand-ins described above.
- **Gatling load tests** — the low-latency design (virtual threads, batch Kafka consumption,
  per-currency-pair lock granularity) has never been load-tested; treat latency claims as design
  intent, not a measured SLA.
- **GitHub Actions deploy pipeline** — `.github/workflows/ci.yml` runs `mvn verify` + Docker build
  on push; there's no deploy-to-cloud pipeline.
- **`docs/DEPLOYMENT.md` / `docs/RUNBOOK.md`** — not written.
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
├── account/           AccountController/Service/Repository — open, deposit, withdraw, convert
├── transfer/          TransferController/Service/Repository — same-bank transfers
├── payment/           Payment, FraudDetection, Ledger, Settlement (saga), Reconciliation
├── loan/              LoanController/Service/Repository — product catalog, originate, repay, accrue
├── trading/            Order, Risk, MatchingEngine (+matching/OrderBook), Execution, PriceFeed
├── notification/       Notification retry/DLQ (@RetryableTopic)
├── ai/                AnomalyDetector + GeminiAnomalyDetector, ClaudeSummarizer + AnthropicClaudeSummarizer
├── chronicle/          Off-heap trade journal reader/writer
├── kafka/              KafkaEventPublisher (+ fallback queue) and every kafka.event.* record
├── config/             Kafka, Security, Trading, Chronicle Queue config + KafkaTopicsProperties
├── exception/          Domain exceptions + GlobalExceptionHandler (central HTTP status mapping)
└── actuator/            Custom health indicator (matching-engine order-book depth)
```

`src/main/resources/`: `application.yml` (+ `-dev`/`-k8s` profiles), `db/migration/V1..V8` (Flyway),
`static/playground.html` (interactive demo UI, permitAll in every profile).

`docs/`: `TRADING_SYSTEM.md` (FX desk lifecycle + matching algorithm), `PAYMENT_SYSTEM.md`
(cross-bank saga + fraud rules), `ACCOUNTS.md` (accounts/transfers/conversion/loans + the ownership
security model). `README.md` and `ARCHITECTURE.md` at the repo root are the two entry points —
start there.

`k8s/` — raw "production-shaped" manifests (assume external managed Postgres/Redis/Kafka).
`helm/trading-platform/` — the actually-deployable/testable chart, with a `devDependencies.enabled`
toggle for local testing. This is the one that's been verified against a real cluster.

## Endpoint inventory

| Method | Path | Notes |
|---|---|---|
| POST/GET | `/v1/accounts`, `/v1/accounts/{id}` | open, get, list (`?clientId=`) |
| POST | `/v1/accounts/{id}/deposit`, `/withdraw`, `/convert` | |
| POST/GET | `/v1/transfers`, `/v1/transfers/{id}` | |
| POST/GET | `/v1/payments`, `/v1/payments/{id}` | idempotent on `idempotencyKey` |
| POST | `/v1/payments/{id}/approve`, `/reject` | `COMPLIANCE_OFFICER` only |
| GET | `/v1/loans/products` | public catalog, no auth required |
| POST/GET | `/v1/loans`, `/v1/loans/{id}` | list via `?clientId=` |
| POST | `/v1/loans/{id}/repay` | |
| POST/GET | `/v1/orders`, `/v1/orders/{id}` | FX desk |
| GET | `/actuator/health`, `/actuator/prometheus` | |
| GET | `/v1/swagger-ui.html`, `/v1/api-docs` | |
| — | `/playground.html` | interactive demo UI |

All client-facing endpoints require `CLIENT` or `ADMIN` role (`AUDITOR`/`COMPLIANCE_OFFICER` get
read-only) **and** pass a `CallerPrincipal` ownership check — see `docs/ACCOUNTS.md §3`.

## Suggested next steps, in priority order

1. **Observability**: wire Jaeger tracing + a Grafana dashboard against the existing Micrometer
   metrics — the metrics themselves already exist, this is deploying the visualization layer.
2. **Gatling load tests** against the FX order and payment pipelines, to turn the latency design
   intent into a measured number.
3. **Real SendGrid/Slack** behind the existing `NotificationService` interface — the retry/DLQ
   mechanism is already correct, only the sender implementations are stand-ins.
4. **`docs/DEPLOYMENT.md` + `docs/RUNBOOK.md`**.
5. Everything in "What's genuinely not done yet" above, roughly in that order.

Read `README.md` and `ARCHITECTURE.md` first — this doc is a status snapshot, those are the
maintained design references.
