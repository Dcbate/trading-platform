package com.dcbate.tradingplatform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private KafkaEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaEventPublisher(kafkaTemplate, new ObjectMapper(), new SimpleMeterRegistry(), 10);
    }

    private CompletableFuture<SendResult<String, String>> failedSend() {
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        future.completeExceptionally(new RuntimeException("broker down"));
        return future;
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<SendResult<String, String>> successfulSend() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    @Test
    void successfulSendDoesNotQueue() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(successfulSend());

        publisher.publish("topic", "key", Map.of("a", "b"));

        assertThat(publisher.fallbackQueueSize()).isZero();
    }

    @Test
    void failedSendIsQueuedForRetry() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedSend());

        publisher.publish("topic", "key", Map.of("a", "b"));

        assertThat(publisher.fallbackQueueSize()).isEqualTo(1);
    }

    @Test
    void drainRetriesQueuedEventAndClearsOnSuccess() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(failedSend())
                .thenReturn(successfulSend());

        publisher.publish("topic", "key", Map.of("a", "b"));
        assertThat(publisher.fallbackQueueSize()).isEqualTo(1);

        publisher.drainFallbackQueue();

        assertThat(publisher.fallbackQueueSize()).isZero();
    }

    @Test
    void drainLeavesStillFailingEventsQueuedForTheNextCycle() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedSend());

        publisher.publish("topic", "key", Map.of("a", "b"));
        publisher.drainFallbackQueue();

        assertThat(publisher.fallbackQueueSize()).isEqualTo(1);
    }
}
