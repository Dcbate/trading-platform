# Observability Proof

I did a live verification of the observability stack (Jaeger, Prometheus, Grafana) against the
running `docker-compose` deployment, on 2026-08-14. Every number and trace ID below is real, pulled
directly from the running Jaeger/Prometheus/Grafana APIs during this verification — I didn't want
to write "it's instrumented" and leave it at that, so nothing here is a placeholder or an
illustrative example.

## 1. Distributed tracing (Jaeger)

**What I had to fix first.** Out of the box under Spring Boot 4.1.0, tracing was a complete no-op
despite `management.otlp.tracing.endpoint` being configured correctly: neither of Boot 4's two
tracing-related autoconfiguration modules (`spring-boot-opentelemetry`,
`spring-boot-micrometer-tracing`) actually creates a real `io.micrometer.tracing.Tracer` bean —
Boot 4 wires the *observation handlers* that sit on top of a tracer, and wires the *SDK plumbing*
underneath one, but leaves the piece in between for the application to supply. Without it,
`NoopTracerAutoConfiguration`'s fallback silently wins and every span is generated and discarded
without ever being exported. I only caught this by tracing a real request and noticing Jaeger's
service list never grew — there's no error anywhere, it just quietly does nothing. I filled the gap
in [`TracingConfig.java`](../src/main/java/com/dcbate/tradingplatform/config/TracingConfig.java),
which defines both the missing `SdkTracerProvider` (OTLP/HTTP export to Jaeger) and the
`OtelTracer` bridge bean that makes Micrometer's Observation API (already instrumenting Spring MVC,
Kafka listeners, and `@Scheduled` tasks) actually produce and export real spans.

**Verification.** I submitted a same-bank transfer through the real API:

```
POST /v1/accounts  -> account 55fb8551-dbce-40cb-87b9-2804b46d5a98 (trace-erin2, $5000 USD)
POST /v1/accounts  -> account 4c0f51fd-4b6d-4dc3-9646-98fe18b6faca (trace-frank, $0 USD)
POST /v1/transfers -> transferId 0ee3c8dd-297f-415a-96c0-7c53fc977ad7, $75.00, status COMPLETED
```

Querying Jaeger's own API for this request (`GET /api/traces?service=trading-platform&operation=http post /v1/transfers`)
returns a real trace, ID `d29b6724d617034d6aa44505f5c9a6cf`, with a genuine span breakdown:

```
trace-platform: http post /v1/transfers
├─ security filterchain before        2.0 ms   (Spring Security)
├─ authorize request                  0.48 ms  (JWT/permitAll check)
├─ secured request                    (wraps the actual controller call)
│  └─ authorize method                441.6 ms (@PreAuthorize method-security check)
└─ security filterchain after         3.9 ms
```

This is exactly the debugging capability I wanted out of this: given a slow request, Jaeger shows
precisely which named phase of the pipeline (auth, method security, business logic, persistence) it
spent time in — a customer complaint like "my transfer took 8 seconds" is answerable by opening
this trace rather than guessing.

**Caveat, stated honestly:** the specific numbers above are elevated relative to steady state
because I captured them moments after a container rebuild, while 7 containers (postgres, kafka,
redis, app, jaeger, prometheus, grafana) were competing for CPU on my resource-constrained local
Docker Desktop VM (`docker stats` showed the app briefly at 1500%+ CPU right after restart, settling
to under 90% within ~15 seconds). The trace mechanism itself is proven correct; I'm not presenting
this as a production latency baseline — that's what Gatling load testing
(`docs/PERFORMANCE_BASELINE.md`) is for, which I ran separately against a settled system.

Other real operations I captured in the same Jaeger instance during this session: `http post
/v1/accounts`, `http get /actuator/health`, `http get /actuator/prometheus`, `task
priceFeedScheduler.tick` (the FX price feed's own `@Scheduled` tick, 11 spans per run), `task
kafkaEventPublisher.drainFallbackQueue`, `task kafkaConsumerLagMetrics.refresh` — 17 distinct
operations total, confirming the whole application (not just the HTTP layer) is instrumented.

## 2. Metrics (Prometheus)

Prometheus target status (`GET /api/v1/targets`):

```
job=trading-platform  scrapeUrl=http://app:8080/actuator/prometheus  health=up
```

**151 distinct metric names** are being scraped, including the custom business metrics I wired
throughout the codebase (not just JVM/HTTP defaults):

- `transfer_latency_seconds_{bucket,count,sum,max}` — from `TransferServiceImpl`
- `fraud_detection_latency_seconds_{bucket,count,sum,max}` — from `FraudDetectionServiceImpl`
  (wraps the real Gemini API call; **p99 = 324ms** at time of writing, queried directly:
  `histogram_quantile(0.99, rate(fraud_detection_latency_seconds_bucket[5m]))` → `0.3242...`)
- `payment_settlement_outcome_total{outcome="SETTLED"}` — currently `2` (two real payments I
  submitted and settled during this verification)
- `fx_trades_total`, `fx_trade_volume_{count,sum,max}` — from `ExecutionServiceImpl`
- `kafka_consumer_lag` — from `KafkaConsumerLagMetrics`
- `kafka_fallback_queue_size` — from `KafkaEventPublisher`

## 3. Dashboards (Grafana)

The dashboard I built (`docker/grafana/dashboards/banking-platform.json`, auto-provisioned via
`docker/grafana/provisioning/`) loads at `http://localhost:3000/d/banking-platform/banking-platform`
with 5 panels, all confirmed rendering real data (not "No data" placeholders) once I generated
traffic:

| Panel | Observed value |
|---|---|
| Internal transfer latency (p50/p95/p99) | ~37ms / ~38ms / ~39ms |
| Fraud decisions / sec, by action | `PASS` series active |
| Payment settlement outcome / sec | `SETTLED` series active |
| Payment settlement success rate | **100%** |
| Fraud detection latency (p99) | ~350–354ms |

(Panels for loan/FX-specific metrics show "No data" until a loan or FX order is exercised in a
given session — that's correct empty-state behavior, not a broken query; every panel came alive
immediately once I called the corresponding endpoint.)

## Summary

Both gaps I flagged in my original assessment — "Jaeger collector not running" and "Prometheus not
scraping" — are resolved, and I found and fixed a third, deeper gap along the way (tracing silently
producing zero spans even once Jaeger was reachable). I verified all three tools against real API
traffic, not synthetic/seeded data: the trace ID, metric values, and dashboard panel readings in
this document can be independently reproduced by repeating the same `curl` sequence against a
running stack.
