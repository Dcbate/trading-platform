# trading-platform

Real-time trading and payment-processing platform. **Phase 1 (this repo, today): the trading
system** — order intake, risk checks, matching, execution, and a compliance trade journal, all
running end-to-end against real Kafka, PostgreSQL, and Redis. Payment processing, Kubernetes/Helm
deployment, and the full observability stack are Phase 2/3 (see [Roadmap](#roadmap)).

## Problem statement

Traders need orders executed in well under 100ms or they lose money. Orders can't be lost or
silently duplicated. The system has to keep working through spikes, crashes, and network
failures, and every trade has to be reconstructable for compliance after the fact.

## Solution overview

Orders flow through five stages, each independently scalable and each recoverable from Kafka if
it crashes:

```
Order API → [orders] → Risk Service → [orders-validated] → Matching Engine → [trades] → Execution Service
                                                                                              │
                                                                                    Postgres + Chronicle journal
```

- **Order API** validates and accepts orders, returns immediately, streams status over WebSocket.
- **Risk Service** rejects orders that breach per-client notional or velocity limits.
- **Matching Engine** holds an in-memory, price/time-priority order book per symbol and matches
  crossing orders.
- **Execution Service** is the single writer of trade outcomes: persists the trade, updates order
  status, and appends to the off-heap trade journal.
- **Price Feed Service** stands in for a real market data feed (none exists for Phase 1) and
  flags anomalous moves.

See [ARCHITECTURE.md](ARCHITECTURE.md) for the full design, [docs/TRADING_SYSTEM.md](docs/TRADING_SYSTEM.md)
for the order lifecycle and matching algorithm in detail.

## Quick start

Requires Docker.

```bash
./scripts/local-setup.sh
```

This builds the app and starts Postgres, Redis, Kafka, and the API via `docker-compose`, then
waits for `/actuator/health` to report `UP`. Once ready:

```bash
# API docs
open http://localhost:8080/v1/swagger-ui.html

# Submit a sell, then a matching buy
curl -s -X POST http://localhost:8080/v1/orders -H 'Content-Type: application/json' \
  -d '{"clientId":"seller-1","symbol":"AAPL","side":"SELL","quantity":10,"price":150.00}'
curl -s -X POST http://localhost:8080/v1/orders -H 'Content-Type: application/json' \
  -d '{"clientId":"buyer-1","symbol":"AAPL","side":"BUY","quantity":10,"price":150.00}'

# Check the resulting fill
curl -s http://localhost:8080/v1/orders/{orderId}
```

The `dev` profile (active by default in `docker-compose.yml`) disables JWT enforcement so these
`curl` calls work without minting a token first — see [Security](#security).

To run locally without Docker: start Postgres/Redis/Kafka yourself and run
`mvn spring-boot:run` (Chronicle Queue needs the JVM `--add-opens` flags already wired into the
Maven build — see `pom.xml`'s `chronicle.jvm.opens` property).

## Architecture diagram

```mermaid
flowchart LR
    Client -->|POST /v1/orders| API[Order API]
    API -->|persist PENDING| DB[(PostgreSQL)]
    API -->|OrderEvent| T1[[orders]]
    T1 --> Risk[Risk Service]
    Risk -->|persist RiskAlert if rejected| DB
    Risk -->|RiskAlertEvent| T2[[risk-alerts]]
    Risk -->|OrderValidatedEvent| T3[[orders-validated]]
    T3 --> Match[Matching Engine]
    Match -->|TradeEvent| T4[[trades]]
    T4 --> Exec[Execution Service]
    Exec -->|persist Trade + order status| DB
    Exec -->|append| CQ[(Chronicle trade journal)]
    Exec -->|push update| WS((WebSocket /v1/orders/stream))
    Feed[Price Feed Service] -->|PriceUpdateEvent| T5[[prices]]
    Feed -->|cache| Redis[(Redis)]
    Risk -.->|anomaly enrichment| Gemini[Gemini API]
    Feed -.->|anomaly enrichment| Gemini
```

## Components & responsibilities

| Component | Responsibility |
|---|---|
| Order API | Validate, persist, publish, stream status |
| Risk Service | Notional + order-velocity limit checks |
| Matching Engine | Price/time-priority matching per symbol |
| Execution Service | Sole writer of trade + order-status truth |
| Trade Journal | Immutable, off-heap, replayable audit log |
| Price Feed Service | Synthetic ticks (Phase 1 stand-in), anomaly detection |

## Design tradeoffs

| Decision | Why | Tradeoff accepted |
|---|---|---|
| Per-symbol synchronized order book instead of a wait-free multi-symbol structure | Correctness of price/time priority is non-negotiable; different symbols never contend | Matching for the *same* symbol is serialized, not lock-free |
| Raw `KafkaConsumer` poll loop for the Matching Engine instead of `@KafkaListener` | Keeps the hot path free of listener-container overhead, matches the batch-poll pattern directly | More manual lifecycle code than a declarative listener |
| Thread affinity (CPU pinning) implemented but disabled by default | Needs a native JNI library, not portable across every dev machine/container | Sub-5ms matching latency claim is unverified without it enabled and benchmarked |
| Synthetic price feed instead of a real market data integration | No real feed exists for Phase 1 | Anomaly detection triggers on synthetic data, not real market events |
| Gemini API wired for real, but purely advisory | Rule-based checks (threshold, velocity) already gate correctness; AI enrichment is a "why" narrative, not a decision-maker | If `GEMINI_API_KEY` is unset, alerts still fire — just without the AI-written explanation |
| `dev` Spring profile disables JWT enforcement | Lets the API be exercised locally without a token-minting step | Must never be the active profile outside local development |

## Latency

The spec's targets (order→Kafka <10ms, matching <5ms, p99 <100ms) describe what this design is
*built for* — the lock-free-per-symbol book, virtual threads, and batched Kafka consumption all
exist for that reason. **They have not been load-tested in Phase 1.** Gatling load tests are a
Phase 3 item; until then, treat the targets as design intent, not a measured SLA.

## Error scenarios & recovery

| Failure | Behavior |
|---|---|
| Order API crashes after publish, before responding | Order already in Kafka; client retries, matching is idempotent on `orderId` |
| Risk/Matching/Execution service crashes | Kafka retains the record; consumer resumes from last committed offset on restart, nothing is lost |
| Kafka unavailable | Order submission fails fast (publish error is logged); no in-memory fallback queue in Phase 1 — see Roadmap |
| Postgres unavailable | Order submission fails (write path); already-matched trades queued in Kafka are retried once Postgres recovers |
| Gemini API unavailable or unset | Anomaly/risk alerts still fire using the rule-based reason; AI enrichment is skipped, not required |
| Redis unavailable | Price feed falls back to the last known seed price per symbol; trading itself doesn't depend on Redis |

## API documentation

Swagger UI: `/v1/swagger-ui.html`. OpenAPI JSON: `/v1/api-docs`.

## Security

- JWT bearer auth (HS256, roles `TRADER` / `ADMIN` / `AUDITOR` / `COMPLIANCE_OFFICER`) enforced
  via `@PreAuthorize` on every endpoint, in every profile.
- The `dev` profile additionally grants those roles to the anonymous principal and permits all
  HTTP requests, so `@PreAuthorize` still runs the same code path as production — it just
  doesn't require a token locally. **Never run `dev` outside local development.**
- No secrets are committed. `JWT_SECRET` and `GEMINI_API_KEY` are environment variables with
  local-only placeholder defaults; production would source both from GCP Secret Manager (Phase 3).

## Monitoring

`/actuator/health` (includes a custom order-book-depth indicator), `/actuator/prometheus`
(Micrometer metrics, including a `price.anomalies` counter). Structured JSON dashboards, Jaeger
tracing, and alert rules are Phase 3.

## Test coverage — stated honestly

Unit tests cover every service's business logic (mocked dependencies) plus the order-book
matching algorithm directly. One Testcontainers integration test proves the full order→trade
pipeline against real Kafka/Postgres/Redis. This is **not** literal 100% line coverage or a
verified SonarQube grade — those require tooling this environment doesn't run. Two known gaps:
the Gemini API's success path (mocking WebFlux's fluent client reliably is disproportionate to
the payoff; the failure/fallback path *is* tested) and `@PreAuthorize` enforcement itself (the
controller unit test bypasses Spring Security entirely by design — see
[OrderControllerTest](src/test/java/com/dcbate/tradingplatform/trading/api/OrderControllerTest.java)).

## Roadmap

- **Phase 2**: Payment & Notification system (Ledger, Settlement saga, Fraud detection, Notification service with retry/DLQ, Reconciliation)
- **Phase 3**: Kubernetes + Helm, GCP Secret Manager/KMS, GitHub Actions deploy pipeline, Jaeger tracing, Grafana dashboards, Gatling load tests, BigQuery analytics streaming, in-memory Kafka-down fallback queue

## Contributing

Standard PR flow: branch, `mvn verify` must pass, follow the
[coding standards](https://github.com/Dcbate/Oracle/blob/main/documentation/CodingStandards.md)
(Controller-Service-Repository, interfaces on every service, JUnit5+Mockito, no nulls).
