package com.dcbate.tradingplatform.trading.event;

import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.config.MatchingEngineProperties;
import com.dcbate.tradingplatform.kafka.event.OrderValidatedEvent;
import com.dcbate.tradingplatform.trading.service.MatchingEngineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.openhft.affinity.AffinityLock;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Owns the matching engine's dedicated Kafka poll loop (Low-Latency Patterns 1, 3 and 4): a raw
 * {@link KafkaConsumer}, polled in batches on a virtual thread, optionally pinned to a CPU core.
 * Deliberately bypasses Spring Kafka's listener container so the hot path has no framework
 * overhead between {@code poll()} and the matching engine.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchingEngineConsumerRunner {

    private final MatchingEngineService matchingEngineService;
    private final ObjectMapper objectMapper;
    private final KafkaTopicsProperties topics;
    private final MatchingEngineProperties matchingEngineProperties;
    private final ExecutorService virtualThreadExecutor;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    private volatile boolean running = true;
    private volatile KafkaConsumer<String, String> consumer;

    @PostConstruct
    public void start() {
        virtualThreadExecutor.submit(this::pollLoop);
    }

    @PreDestroy
    public void stop() {
        running = false;
        KafkaConsumer<String, String> activeConsumer = this.consumer;
        if (activeConsumer != null) {
            activeConsumer.wakeup();
        }
    }

    private void pollLoop() {
        AffinityLock affinityLock = acquireAffinityLockIfEnabled();
        try (KafkaConsumer<String, String> kafkaConsumer = buildConsumer()) {
            this.consumer = kafkaConsumer;
            kafkaConsumer.subscribe(List.of(topics.ordersValidated()));

            while (running) {
                ConsumerRecords<String, String> records =
                        kafkaConsumer.poll(Duration.ofMillis(matchingEngineProperties.pollTimeoutMs()));
                if (!records.isEmpty()) {
                    records.forEach(this::processRecord);
                    kafkaConsumer.commitSync();
                }
            }
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
        } catch (Exception e) {
            log.error("Matching engine poll loop terminated unexpectedly: {}", e.getMessage(), e);
        } finally {
            if (affinityLock != null) {
                affinityLock.release();
            }
        }
    }

    private KafkaConsumer<String, String> buildConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "matching-engine");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, matchingEngineProperties.batchSize());
        return new KafkaConsumer<>(props);
    }

    private void processRecord(ConsumerRecord<String, String> record) {
        try {
            matchingEngineService.match(objectMapper.readValue(record.value(), OrderValidatedEvent.class));
        } catch (Exception e) {
            log.error("Failed to process validated order, skipping: {}", e.getMessage());
        }
    }

    private AffinityLock acquireAffinityLockIfEnabled() {
        if (!matchingEngineProperties.threadAffinity().enabled()) {
            return null;
        }
        try {
            AffinityLock lock = AffinityLock.acquireLock(matchingEngineProperties.threadAffinity().core());
            log.info("Matching engine pinned to CPU core {}", matchingEngineProperties.threadAffinity().core());
            return lock;
        } catch (Throwable t) {
            log.warn("Thread affinity requested but unavailable on this host, continuing without pinning: {}",
                    t.getMessage());
            return null;
        }
    }
}
