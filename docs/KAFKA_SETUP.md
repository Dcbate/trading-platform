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

## The fallback queue is durable now, not just in-memory

The above two fixes make a *transient* Kafka hiccup fast to detect. They don't help if Kafka is down
long enough that the app itself restarts in the meantime — the fallback queue used to be a plain
`LinkedBlockingQueue`, which is gone the instant the JVM is. I went back and fixed that properly,
and in doing so found three more bugs I want to document honestly, because I found all of them by
actually trying to break the thing, not by reading the code and spotting them.

### 1. The queue itself: Chronicle Queue instead of an in-memory buffer

`KafkaEventPublisher`'s fallback queue is now backed by a
[Chronicle Queue](https://github.com/OpenHFT/Chronicle-Queue) file — the same off-heap,
memory-mapped library already used for the trade compliance journal
([ARCHITECTURE.md §4](../ARCHITECTURE.md#4-low-latency-patterns)), reused here for a different
reason: not GC-avoidance this time, but genuine on-disk durability. A **named tailer**
(`fallbackQueue.createTailer("kafka-fallback-consumer")`) persists its own read position to disk
alongside the data, so on restart it resumes exactly where it left off instead of either replaying
already-sent events or losing track of ones that were never sent.

**Proof, not just a claim:** I actually killed the Kafka container, queued a deposit through the
real API (confirmed it landed in the fallback queue via the `kafka.fallback.queue.size` gauge),
restarted the app container while Kafka was still down, brought Kafka back up, and watched the
pre-restart backlog get found and drained automatically — no manual intervention, no lost event.

**What's still a real limitation, stated plainly:** it's still single-instance. A multi-instance
deployment would need this backed by something shared (Redis, a distributed queue) instead, since
each instance only ever drains its own local queue file.

### 2. Two separate ways a Kafka outage could crash the whole app at boot

Testing the fix above the honest way — actually taking Kafka down and watching what happened —
turned up something worse than "the fallback queue isn't durable": with Kafka down, **the app
didn't start at all**. Two independent causes, both eager, both only reachable by testing against a
genuinely unreachable broker rather than a slow one:

- [`KafkaConsumerLagMetrics`](../src/main/java/com/dcbate/tradingplatform/kafka/KafkaConsumerLagMetrics.java)'s
  constructor called `AdminClient.create(props)` directly. `AdminClient.create()` resolves the
  broker address eagerly and throws `KafkaException: Failed to create new KafkaAdminClient` if it
  can't — and since this happens during Spring's `preInstantiateSingletons()` phase of
  `ApplicationContext.refresh()`, an unresolvable broker took the *entire* application context down
  with it, not just this one metrics gauge.
- Spring Kafka's `@KafkaListener` containers (all six consumers — `OrderEventConsumer`,
  `MatchingEngineConsumerRunner` is the one exception, see below, plus `PaymentEventConsumer`,
  `SettlementEventConsumer`, `TradeEventConsumer`, `NotificationEventConsumer`) default to starting
  synchronously as part of the same context-refresh lifecycle, via `KafkaListenerEndpointRegistry`
  (a `SmartLifecycle` bean Spring auto-starts). So even after fixing the first cause, the app *still*
  failed to boot with Kafka down — a second, independent crash path through the exact same symptom.

I fixed both with the lazy-connect pattern already established elsewhere in this codebase for
exactly this situation (see `ai/mcp/McpToolClient` — never let a downstream dependency's
unavailability block or crash something that doesn't strictly need it up yet):

- `KafkaConsumerLagMetrics` now builds its `AdminClient` lazily, on first scheduled poll, not in the
  constructor — a Kafka outage at boot now just means the lag gauge stays unpopulated until Kafka's
  reachable, instead of crashing startup.
- Both `ConcurrentKafkaListenerContainerFactory` beans in `KafkaConfig` now set
  `autoStartup=false`, and a new
  [`KafkaListenerStartupRunner`](../src/main/java/com/dcbate/tradingplatform/kafka/KafkaListenerStartupRunner.java)
  scheduled task starts each listener container individually once Kafka's actually reachable,
  retrying every 5 seconds per container (not as one all-or-nothing group, so one persistently
  broken container can't block the others from coming up). I deliberately check and start each
  `MessageListenerContainer` directly rather than calling `KafkaListenerEndpointRegistry.start()` —
  the registry's own `isRunning()` flips `true` as soon as its (harmless, skipped-because-
  autoStartup=false) startup pass completes during context refresh, regardless of whether any
  container actually connected, so checking the registry itself would have looked successful on the
  very first tick and never retried again. I caught that by testing it, not by reading Spring
  Kafka's source ahead of time.
  
  (`MatchingEngineConsumerRunner` was never part of this problem — it deliberately bypasses Spring
  Kafka's listener containers entirely for hot-path reasons, see
  [ARCHITECTURE.md §4](../ARCHITECTURE.md#4-low-latency-patterns), and already builds its raw
  `KafkaConsumer` inside an async task submitted from `@PostConstruct`, not the constructor itself
  — so it was already safe.)

**Proof:** with the fixes in place, I killed Kafka, deployed a fresh app container against it, and
confirmed it reaches `healthy` anyway (previously: crash loop). Bringing Kafka back up, I checked
consumer group membership directly on the broker
(`kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list`) — all twelve expected groups
(`risk-service`, `matching-engine`, `execution-service`, `fraud-detection-service`,
`settlement-service`, `notification-service` and its five `@RetryableTopic` retry/DLQ variants)
showed up, with `risk-service` observed getting real partition assignments in the live logs, no app
restart required.

### 3. The fallback queue's own exception handling had a gap

The very fix I was trying to prove — "a failed publish gets queued for retry" — turned out to have
its own hole. `KafkaEventPublisher.send()` used to catch `org.springframework.kafka.KafkaException`
(Spring's wrapper). But a producer that fails to *construct* at all — e.g. the broker hostname isn't
resolvable, which is exactly what happens the moment Kafka is down — throws the raw
`org.apache.kafka.common.KafkaException` instead: a different class with the same simple name, not
a subtype of the one I was catching. That specific failure mode escaped uncaught straight past the
fallback queue and out to the caller as a 500 — the exact scenario the queue exists to prevent. I
only found it by deliberately taking Kafka down and hitting the API and watching a 500 come back
instead of a 200; reading the code wouldn't have surfaced it, since both exception types genuinely
look like the right thing to catch at a glance. Fixed by broadening the catch to `Exception`, with a
regression test that specifically throws the raw Apache Kafka exception type to pin it.

### Why I'm documenting three bugs instead of just the one fix I set out to make

I went in to make one change — swap an in-memory queue for a durable one — and actually testing that
change (not just unit-testing it in isolation, but killing the real dependency and watching what
happened) surfaced three more real problems that unit tests alone wouldn't have caught, because each
one only manifests when Kafka is *genuinely* unreachable, not merely slow. That's the case for
testing resilience work by actually breaking the thing you're claiming to be resilient to, rather
than trusting that the code review reads correctly.
