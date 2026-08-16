package com.dcbate.tradingplatform.chronicle;

import net.openhft.chronicle.queue.ChronicleQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the {@link ChronicleQueue} instances the trade journal and the Kafka fallback queue each use. */
@Configuration
public class ChronicleQueueConfig {

    /**
     * Single memory-mapped, off-heap append-only queue backing the trade journal (Low-Latency
     * Pattern 6). Trades never touch the Java heap here, so a GC pause can never delay the
     * compliance audit write.
     */
    @Bean(destroyMethod = "close")
    public ChronicleQueue tradeJournalQueue(@Value("${chronicle.trade-journal.path}") String path) {
        return ChronicleQueue.single(path);
    }

    /**
     * Separate queue file from {@link #tradeJournalQueue} — unrelated data, kept as its own
     * physical log rather than conflated into one stream. Backs {@code KafkaEventPublisher}'s
     * fallback path, so a queued event survives an app restart instead of being lost in-memory.
     */
    @Bean(destroyMethod = "close")
    public ChronicleQueue kafkaFallbackQueue(@Value("${chronicle.kafka-fallback.path}") String path) {
        return ChronicleQueue.single(path);
    }
}
