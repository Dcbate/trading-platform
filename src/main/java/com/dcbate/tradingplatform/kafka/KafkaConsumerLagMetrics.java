package com.dcbate.tradingplatform.kafka;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls every consumer group in the platform via {@link AdminClient} and exposes the summed
 * (committed-offset vs. end-offset) lag as the {@code kafka.consumer.lag} gauge — "how far behind
 * is event processing, in messages." A per-group failure (group not yet created, broker hiccup)
 * is logged and skipped rather than failing the whole scrape; the gauge simply reflects whatever
 * groups answered on the last successful poll.
 */
@Slf4j
@Component
public class KafkaConsumerLagMetrics {

    // One entry per @KafkaListener groupId / raw-KafkaConsumer group.id in the app — see
    // OrderEventConsumer, MatchingEngineConsumerRunner, TradeEventConsumer,
    // PaymentEventConsumer, SettlementEventConsumer, NotificationEventConsumer.
    private static final List<String> CONSUMER_GROUPS = List.of(
            "risk-service", "matching-engine", "execution-service",
            "fraud-detection-service", "settlement-service", "notification-service");

    private final String bootstrapServers;
    private final AtomicLong totalLag = new AtomicLong();
    // Lazy, like McpToolClient: AdminClient.create() resolves bootstrap.servers eagerly and
    // throws if DNS can't resolve it yet — constructing it here instead of in the constructor
    // means a Kafka outage at app-startup time degrades this one gauge, not the whole app.
    private volatile AdminClient adminClient;

    public KafkaConsumerLagMetrics(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers, MeterRegistry meterRegistry) {
        this.bootstrapServers = bootstrapServers;
        meterRegistry.gauge("kafka.consumer.lag", totalLag);
    }

    @Scheduled(fixedDelayString = "${kafka.consumer-lag.poll-interval-ms:10000}")
    public void refresh() {
        AdminClient client;
        try {
            client = connectedClient();
        } catch (Exception e) {
            log.debug("Kafka admin client unavailable, skipping this consumer-lag poll: {}", e.getMessage());
            return;
        }

        long lag = 0;
        for (String groupId : CONSUMER_GROUPS) {
            try {
                lag += lagForGroup(client, groupId);
            } catch (Exception e) {
                log.debug("Could not compute Kafka lag for consumer group {}: {}", groupId, e.getMessage());
            }
        }
        totalLag.set(lag);
    }

    private synchronized AdminClient connectedClient() {
        AdminClient client = adminClient;
        if (client == null) {
            Properties props = new Properties();
            props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            client = AdminClient.create(props);
            adminClient = client;
        }
        return client;
    }

    private long lagForGroup(AdminClient client, String groupId) throws Exception {
        Map<TopicPartition, OffsetAndMetadata> committed = client.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get(5, java.util.concurrent.TimeUnit.SECONDS);
        if (committed.isEmpty()) {
            return 0;
        }

        Map<TopicPartition, OffsetSpec> endOffsetRequests = committed.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
        Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> endOffsets =
                client.listOffsets(endOffsetRequests).all().get(5, java.util.concurrent.TimeUnit.SECONDS);

        long groupLag = 0;
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : committed.entrySet()) {
            ListOffsetsResult.ListOffsetsResultInfo end = endOffsets.get(entry.getKey());
            if (end == null) {
                continue;
            }
            groupLag += Math.max(0, end.offset() - entry.getValue().offset());
        }
        return groupLag;
    }

    @PreDestroy
    public void close() {
        AdminClient client = adminClient;
        if (client != null) {
            client.close(Duration.ofSeconds(5));
        }
    }
}
