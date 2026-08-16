package com.dcbate.tradingplatform.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.openhft.chronicle.queue.ChronicleQueue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @TempDir
    private Path queuePath;

    private KafkaEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = newPublisher(queuePath);
    }

    private KafkaEventPublisher newPublisher(Path path) {
        ChronicleQueue queue = ChronicleQueue.single(path.resolve("fallback").toString());
        return new KafkaEventPublisher(kafkaTemplate, new ObjectMapper(), queue, new SimpleMeterRegistry(), 10);
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
    void aSynchronousThrowFromSendIsQueuedForRetryEvenAsTheRawKafkaClientException() {
        // Regression pin: producer construction failing outright (e.g. bootstrap.servers can't
        // resolve at all) throws org.apache.kafka.common.KafkaException synchronously from
        // send() itself, NOT org.springframework.kafka.KafkaException — catching only the Spring
        // wrapper type let this exact failure mode escape uncaught to the caller instead of
        // queuing, silently defeating the whole fallback queue for this failure class.
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenThrow(new org.apache.kafka.common.KafkaException("Failed to construct kafka producer"));

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

    @Test
    void queuedEventSurvivesRecreatingThePublisherAgainstTheSameQueueFile() {
        // The whole point of backing the fallback queue with Chronicle Queue instead of an
        // in-memory buffer: a queued event isn't lost if the process restarts before it drains.
        // Standing up a second KafkaEventPublisher against the same on-disk queue simulates that.
        // Its own pendingCount gauge starts back at zero (an in-process counter, same limitation
        // the old in-memory queue had) — the durable proof is that draining it still finds and
        // resends the event nothing in this second instance ever explicitly enqueued.
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(failedSend());
        publisher.publish("topic", "key", Map.of("a", "b"));
        assertThat(publisher.fallbackQueueSize()).isEqualTo(1);

        KafkaEventPublisher restarted = newPublisher(queuePath);
        clearInvocations(kafkaTemplate);
        when(kafkaTemplate.send(anyString(), anyString(), anyString())).thenReturn(successfulSend());

        restarted.drainFallbackQueue();

        verify(kafkaTemplate).send(eq("topic"), eq("key"), anyString());
    }
}
