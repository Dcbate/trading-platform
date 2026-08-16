# Kafka Setup

## Topic provisioning: fixed, not a workaround

Earlier in this project's Spring Boot 4.1.0 migration, the app's Kafka `AdminClient` appeared unable
to create the application topics (`orders`, `payments`, `loans`, etc. — the full list is defined
as `NewTopic` beans in
[`KafkaConfig.java`](../src/main/java/com/dcbate/tradingplatform/config/KafkaConfig.java)), and I
created topics manually via the Kafka CLI as a stopgap. **That workaround is no longer needed.** I
tracked down the root cause and fixed it: Spring Boot 4 split Kafka's autoconfiguration (including
the `KafkaAdmin` bean responsible for auto-creating `NewTopic` beans on startup) out of
`spring-boot-autoconfigure` into a new standalone module, `spring-boot-kafka` — the same pattern
Boot 4 used for Flyway (`spring-boot-flyway`), Jackson (`spring-boot-jackson`), and actuator health
(`spring-boot-health`). I depended on `spring-kafka` directly but never picked up this new
autoconfiguration module, so `KafkaAdmin` was silently absent and no topics were ever created.

Fixed by adding to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-kafka</artifactId>
</dependency>
```

**I verified this works, twice**: once by deleting all 20 topics from a running broker and
confirming a plain app restart recreated them all with the correct partition counts (`payments`=20,
`orders`=10, etc. — matching the `NewTopic` bean definitions exactly), and again from a completely
fresh Kafka 4.3.1 broker after the Postgres/Redis/Kafka version bump (see `README.md`). No manual
`kafka-topics.sh` step is needed for a normal `docker compose up` — the app provisions its own
topics on startup.

## Topics

| Topic | Partitions | Producer(s) | Consumer(s) |
|---|---|---|---|
| `orders` | 10 | `OrderServiceImpl` | `OrderEventConsumer` → `RiskService` |
| `orders-validated` | 10 | `RiskServiceImpl` | `MatchingEngineConsumerRunner` (raw `KafkaConsumer`, bypasses the listener container for lower latency) |
| `trades` | 10 | Matching engine | `TradeEventConsumer` → `ExecutionService` (sequential, not parallel — a batch can contain multiple fills against the same order) |
| `prices` | 5 | `PriceFeedServiceImpl` (simulated tick, every 2s) | broadcast only, no in-app consumer |
| `risk-alerts` | 5 | `RiskServiceImpl` | broadcast/audit only |
| `payments` | 20 | `PaymentServiceImpl` | `PaymentEventConsumer` → `FraudDetectionService` |
| `payments-validated` | 10 | `FraudDetectionServiceImpl` | `SettlementEventConsumer` → `SettlementService` |
| `ledger-entries` | 10 | `LedgerServiceImpl` | broadcast/audit only |
| `fraud-alerts` | 5 | `FraudDetectionServiceImpl` | broadcast/audit only |
| `notifications` | 5 | `FraudDetectionServiceImpl`, `SettlementServiceImpl`, `ReconciliationServiceImpl` | `NotificationEventConsumer`, with Spring Kafka's `@RetryableTopic` (exponential backoff 1s/2s/4s/8s/16s, 6 tries total) |
| `notifications-dlq` | auto-created by `@RetryableTopic` | — | dead-letter sink after retries exhaust |
| `account-activity` | 10 | `AccountServiceImpl` | broadcast/audit only |
| `transfers` | 10 | `TransferServiceImpl` | broadcast/audit only |
| `loans` | 5 | `LoanServiceImpl` | broadcast/audit only |

Broker auto-create-topics is deliberately disabled (`KAFKA_AUTO_CREATE_TOPICS_ENABLE: false` in
`docker-compose.yml`) so these `NewTopic` beans are the sole source of truth for partition counts —
a race between the app's provisioning and the broker's own auto-create previously caused a topic
to get created with the wrong partition count, which is why this is explicit rather than
convenient.

## A related bug I found in the same investigation

While diagnosing the above, I found and fixed a genuine production bug in
[`KafkaEventPublisher`](../src/main/java/com/dcbate/tradingplatform/kafka/KafkaEventPublisher.java):
`KafkaConfig.producerFactory()` builds its own hardcoded properties map that bypasses
`application.yml` entirely. Combined with `acks=all` + idempotence, `KafkaProducer.send()` can block
the calling thread — an HTTP request thread, for anything going through
`KafkaEventPublisher.publish()` — for up to Kafka's default 60-second `max.block.ms` if topic
metadata isn't yet cached (e.g. right after topics are first created). I caught this specifically
because I traced a request through Jaeger and noticed a suspiciously long span — see
`OBSERVABILITY_PROOF.md`. Fixed by capping `max.block.ms` at 3 seconds directly in the producer's
properties and catching Spring's `KafkaException` in `KafkaEventPublisher.send()` to route a
metadata-wait timeout into the existing fallback-retry queue, the same path used for any other send
failure — so a slow broker now degrades to "queued for retry" within 3 seconds instead of stalling a
customer-facing request for up to a minute.
