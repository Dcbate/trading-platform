package com.dcbate.tradingplatform.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin JSON-over-Kafka publisher shared by every producer in the platform. A failed send is
 * queued in a bounded, in-memory buffer and retried on a schedule — a single-instance safety net
 * for a transient Kafka outage, not a durable store: queued events are lost on restart, and a
 * multi-instance deployment would need this backed by something shared (Redis, a local disk
 * spool) instead. When the buffer itself fills up, the oldest-style behavior is to drop and log
 * loudly rather than block the caller or grow unbounded.
 */
@Slf4j
@Component
public class KafkaEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final BlockingQueue<PendingRecord> fallbackQueue;

    public KafkaEventPublisher(
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${kafka.fallback-queue.capacity:10000}") int fallbackQueueCapacity) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.fallbackQueue = new LinkedBlockingQueue<>(fallbackQueueCapacity);
        meterRegistry.gauge("kafka.fallback.queue.size", fallbackQueue, BlockingQueue::size);
    }

    public void publish(String topic, String key, Object event) {
        try {
            send(topic, key, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event for topic={} key={}: {}", topic, key, e.getMessage());
        }
    }

    private void send(String topic, String key, String payload) {
        kafkaTemplate.send(topic, key, payload).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Failed to publish to topic={} key={}: {}", topic, key, ex.getMessage());
                enqueueForRetry(topic, key, payload);
            }
        });
    }

    private void enqueueForRetry(String topic, String key, String payload) {
        if (!fallbackQueue.offer(new PendingRecord(topic, key, payload))) {
            log.error("Fallback queue full (capacity reached); dropping event for topic={} key={}", topic, key);
        }
    }

    /**
     * Retries whatever the fallback queue held at the start of this run. Only that many items are
     * drained per cycle — anything re-queued by a retry that fails again waits for the next cycle,
     * so a persistent outage can't spin this into a tight loop.
     */
    @Scheduled(fixedDelayString = "${kafka.fallback-queue.drain-interval-ms:5000}")
    public void drainFallbackQueue() {
        int attemptsThisCycle = fallbackQueue.size();
        for (int i = 0; i < attemptsThisCycle; i++) {
            PendingRecord pending = fallbackQueue.poll();
            if (pending == null) {
                return;
            }
            send(pending.topic(), pending.key(), pending.payload());
        }
    }

    public int fallbackQueueSize() {
        return fallbackQueue.size();
    }

    private record PendingRecord(String topic, String key, String payload) {
    }
}
