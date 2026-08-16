package com.dcbate.tradingplatform.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.queue.ChronicleQueue;
import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Thin JSON-over-Kafka publisher shared by every producer in the platform. A failed send is
 * durably queued (Chronicle Queue — see {@code ChronicleQueueConfig.kafkaFallbackQueue}) and
 * retried on a schedule — a safety net for a transient Kafka outage that survives an app restart,
 * unlike an in-memory buffer. Still single-instance: a multi-instance deployment would need this
 * backed by something shared (Redis, a distributed queue) instead, since each instance only drains
 * its own local queue file.
 */
@Slf4j
@Component
public class KafkaEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ChronicleQueue fallbackQueue;
    private final ExcerptTailer fallbackTailer;
    private final int maxDrainPerCycle;
    private final AtomicLong pendingCount = new AtomicLong();

    public KafkaEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            @Qualifier("kafkaFallbackQueue") ChronicleQueue fallbackQueue,
            MeterRegistry meterRegistry,
            @Value("${kafka.fallback-queue.max-drain-per-cycle:1000}") int maxDrainPerCycle) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.fallbackQueue = fallbackQueue;
        // A named tailer persists its read position to disk alongside the queue itself, so a
        // restart mid-outage resumes draining from exactly where it left off instead of re-reading
        // (or losing track of) whatever was already queued.
        this.fallbackTailer = fallbackQueue.createTailer("kafka-fallback-consumer");
        this.maxDrainPerCycle = maxDrainPerCycle;
        meterRegistry.gauge("kafka.fallback.queue.size", pendingCount, AtomicLong::get);
    }

    public void publish(String topic, String key, Object event) {
        try {
            send(topic, key, objectMapper.writeValueAsString(event));
        } catch (JacksonException e) {
            log.error("Failed to serialize event for topic={} key={}: {}", topic, key, e.getMessage());
        }
    }

    private void send(String topic, String key, String payload) {
        try {
            kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish to topic={} key={}: {}", topic, key, ex.getMessage());
                    enqueueForRetry(topic, key, payload);
                }
            });
        } catch (KafkaException e) {
            // send() itself blocks the caller (up to the producer's max.block.ms, see
            // KafkaConfig.producerFactory) waiting on topic metadata before it can even return a
            // Future; if that wait times out, Spring wraps it and throws synchronously rather than
            // surfacing via whenComplete. Same fallback path as an async failure, so a slow/
            // unavailable broker never stalls the calling HTTP request.
            log.error("Failed to submit send for topic={} key={}: {}", topic, key, e.getMessage());
            enqueueForRetry(topic, key, payload);
        }
    }

    private void enqueueForRetry(String topic, String key, String payload) {
        try {
            String serialized = objectMapper.writeValueAsString(new PendingRecord(topic, key, payload));
            try (ExcerptAppender appender = fallbackQueue.createAppender()) {
                appender.writeText(serialized);
            }
            pendingCount.incrementAndGet();
        } catch (Exception e) {
            // A durable-queue write failure (e.g. disk full) must never propagate: this already
            // runs on the Kafka producer's callback thread or the scheduled drain thread, and
            // either one dying silently cancels all its future work until the app is restarted.
            log.error("Failed to durably queue event for retry, topic={} key={}: {}", topic, key, e.getMessage());
        }
    }

    /**
     * Retries up to {@code maxDrainPerCycle} entries per run — a persistent outage re-queues
     * failures (via {@link #enqueueForRetry}) rather than blocking here, so it can't spin this
     * into a tight loop; anything past the cap waits for the next cycle.
     */
    @Scheduled(fixedDelayString = "${kafka.fallback-queue.drain-interval-ms:5000}")
    public void drainFallbackQueue() {
        for (int i = 0; i < maxDrainPerCycle; i++) {
            PendingRecord pending = readNext();
            if (pending == null) {
                return;
            }
            send(pending.topic(), pending.key(), pending.payload());
        }
    }

    private PendingRecord readNext() {
        try {
            String payload = fallbackTailer.readText();
            if (payload == null) {
                return null;
            }
            pendingCount.decrementAndGet();
            return objectMapper.readValue(payload, PendingRecord.class);
        } catch (Exception e) {
            log.error("Failed to read a fallback-queue entry, skipping: {}", e.getMessage());
            return null;
        }
    }

    public long fallbackQueueSize() {
        return pendingCount.get();
    }

    private record PendingRecord(String topic, String key, String payload) {
    }
}
