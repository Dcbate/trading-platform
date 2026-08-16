package com.dcbate.tradingplatform.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Producer/consumer factories and topic provisioning shared across trading and payments. The
 * {@code NewTopic} beans here are the source of truth for partition counts — the broker's
 * auto-create-topics is deliberately disabled in {@code docker-compose.yml} so a race between
 * the two can never silently create a topic with the wrong partition count (this bit us once
 * during Phase 1 development).
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @org.springframework.beans.factory.annotation.Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        // send() blocks the calling thread (an HTTP request thread, for callers off KafkaEventPublisher)
        // waiting on topic metadata before it can even hand back a Future. The client default is 60s;
        // fail fast instead so a broker that's briefly slow to resolve metadata (e.g. right after topics
        // are first created) can't stall a request for far longer than any caller should wait.
        // KafkaEventPublisher already treats this the same as any other send failure and queues it for retry.
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 3000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    /** Batch listener container factory used by the Risk and Execution services (pattern 3). */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> batchListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        // See KafkaListenerStartupRunner — containers connect once Kafka is actually reachable
        // instead of auto-starting during context refresh, where a broker outage would otherwise
        // fail application startup entirely.
        factory.setAutoStartup(false);
        return factory;
    }

    /**
     * Record-level (non-batch) factory for {@code @RetryableTopic} listeners — the notification
     * retry/DLQ mechanism operates per-record, not per-batch.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> retryableListenerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setAutoStartup(false);
        return factory;
    }

    @Bean
    public NewTopic ordersTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.orders(), 10, (short) 1);
    }

    @Bean
    public NewTopic ordersValidatedTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.ordersValidated(), 10, (short) 1);
    }

    @Bean
    public NewTopic tradesTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.trades(), 10, (short) 1);
    }

    @Bean
    public NewTopic pricesTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.prices(), 5, (short) 1);
    }

    @Bean
    public NewTopic riskAlertsTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.riskAlerts(), 5, (short) 1);
    }

    @Bean
    public NewTopic paymentsTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.payments(), 20, (short) 1);
    }

    @Bean
    public NewTopic paymentsValidatedTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.paymentsValidated(), 10, (short) 1);
    }

    @Bean
    public NewTopic ledgerEntriesTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.ledgerEntries(), 10, (short) 1);
    }

    @Bean
    public NewTopic fraudAlertsTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.fraudAlerts(), 5, (short) 1);
    }

    @Bean
    public NewTopic notificationsTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.notifications(), 5, (short) 1);
    }

    @Bean
    public NewTopic accountActivityTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.accountActivity(), 10, (short) 1);
    }

    @Bean
    public NewTopic transfersTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.transfers(), 10, (short) 1);
    }

    @Bean
    public NewTopic loansTopic(KafkaTopicsProperties topics) {
        return new NewTopic(topics.loans(), 5, (short) 1);
    }
}
