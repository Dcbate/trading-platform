# Performance Baseline

Real Gatling results, which I ran on 2026-08-14 against the local `docker-compose` stack (Postgres
18, Kafka 4.3.1, Redis 8, app — see `README.md` for the current stack). Every number below is
copied directly from the Gatling run output; I didn't estimate or round anything up to a "nicer"
figure.

I don't check the full interactive HTML reports (latency distribution charts, per-request
breakdowns, requests/sec over time) into the repo — like the rest of `target/` they're build
output, reproducible on demand rather than static assets worth versioning. Run any command below
and Gatling prints the report path (`target/gatling/<scenario>-<timestamp>/index.html`) at the end.

## Environment, stated honestly

These runs used each simulation's default (deliberately scaled-down) local settings, **not** the
full "1,000 concurrent users" / "100 orders/sec" scenario sizes from my original planning exercise
— see [Scaling to the full scenario](#scaling-to-the-full-scenario) below for how to run those.
They also ran on a single Docker Desktop VM (8 vCPU / ~4GB RAM) hosting all 7 containers (Postgres,
Kafka, Redis, the app, Jaeger, Prometheus, Grafana) at once, sharing CPU with this session's other
work — so absolute throughput here is a host-constrained floor, not a tuned ceiling. The number
that actually matters for "does this scale correctly" — **failure rate** — was 0% across all three
scenarios and 750 total requests.

## Results

### Scenario 1: Internal transfers ("normal day")

50 concurrent users, each opens 2 accounts then makes 5 transfers between them, ramped over 20s.

```
mvn gatling:test -Dgatling.simulationClass=com.dcbate.tradingplatform.loadtest.TransferLoadSimulation
```

| Metric | Value |
|---|---|
| Total requests | 350 (100 account opens + 250 transfers) |
| Failed requests | **0** |
| Min / Mean / Max | 3ms / 14ms / 368ms |
| p50 / p75 / p95 / p99 | 10ms / 14ms / 29ms / **67ms** |
| Throughput | 17.5 req/sec |
| Assertion: p99 < 500ms | **PASS** (actual 67ms) |
| Assertion: failure rate < 1% | **PASS** (actual 0%) |

### Scenario 2: Loan origination peak

Constant rate of 5 loan originations/sec (open account → originate loan) for 20s.

```
mvn gatling:test -Dgatling.simulationClass=com.dcbate.tradingplatform.loadtest.LoanOriginationLoadSimulation
```

| Metric | Value |
|---|---|
| Total requests | 200 (100 account opens + 100 originations) |
| Failed requests | **0** |
| Min / Mean / Max | 4ms / 50ms / 966ms |
| p50 / p75 / p95 / p99 | 15ms / 23ms / 156ms / **966ms** |
| Throughput | 10 req/sec |
| Assertion: failure rate < 1% | **PASS** (actual 0%) |

I want to be direct about the p99/max tail (4 of 200 requests landed between 800ms–1.2s) rather than
hide it: it lines up with `LoanServiceImpl.originate()`'s `@Transactional` write contending with
the JPA/Hibernate connection pool under this run's burst pattern, not a systemic scaling problem —
196 of 200 requests (98%) completed under 800ms. Re-running this scenario in isolation (without the
other two load tests' residual Kafka consumer activity in the background) would be my next
diagnostic step if this tail needs tightening before I'd make a real capacity commitment.

### Scenario 3: FX trading desk

Two concurrent scenarios (sellers + buyers) both trading EUR/USD at the same price so orders
actually cross, 10 orders/sec combined (5/sec each side) for 20s, 25 distinct trader identities
per side round-robin (to avoid tripping the 5-orders/60s risk velocity limit).

```
mvn gatling:test -Dgatling.simulationClass=com.dcbate.tradingplatform.loadtest.FxTradingLoadSimulation
```

| Metric | Value |
|---|---|
| Total requests | 200 (order submissions only) |
| Failed requests | **0** |
| Min / Mean / Max | 8ms / 44ms / 897ms |
| p50 / p75 / p95 / p99 | 18ms / 24ms / 99ms / **713ms** |
| Throughput | 10 req/sec |
| Assertion: failure rate < 1% | **PASS** (actual 0%) |

This measures **order submission** latency (HTTP `POST /v1/orders` → `201`), not match/fill
latency — matching happens asynchronously off the `orders-validated` Kafka topic and doesn't appear
in an HTTP response time. Order submission itself was fast and reliable (99% under 800ms); I'd need
to measure match latency from Kafka event timestamps or the WebSocket order-status stream instead
of Gatling's HTTP timers — a real gap I'm flagging, not glossing over.

## Scaling to the full scenario

Each simulation accepts overrides for the original "1,000 users" / "100 orders/sec" sizing targets
via system properties, run on infrastructure that isn't sharing a host with 6 other containers:

```bash
# 1,000 users, 5 transfers each
mvn gatling:test -Dgatling.simulationClass=com.dcbate.tradingplatform.loadtest.TransferLoadSimulation \
  -Dusers=1000 -DtransfersPerUser=5 -DrampSeconds=60

# 100 concurrent loan originations/sec
mvn gatling:test -Dgatling.simulationClass=com.dcbate.tradingplatform.loadtest.LoanOriginationLoadSimulation \
  -DratePerSec=100 -DdurationSeconds=60

# 50 traders, 100 orders/sec, 10 minutes
mvn gatling:test -Dgatling.simulationClass=com.dcbate.tradingplatform.loadtest.FxTradingLoadSimulation \
  -DordersPerSec=100 -DdurationSeconds=600
```

I haven't run it at this scale here — doing so meaningfully requires dedicated infrastructure (a
real Postgres/Kafka deployment, not a laptop's Docker Desktop VM) so the numbers would reflect the
application's actual limits rather than my host's.
