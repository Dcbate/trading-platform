package com.dcbate.tradingplatform.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

@ExtendWith(MockitoExtension.class)
class KafkaListenerStartupRunnerTest {

    @Mock
    private KafkaListenerEndpointRegistry registry;

    @Mock
    private MessageListenerContainer container;

    private KafkaListenerStartupRunner runner;

    @BeforeEach
    void setUp() {
        runner = new KafkaListenerStartupRunner(registry);
        when(registry.getListenerContainers()).thenReturn(List.of(container));
    }

    @Test
    void startsAContainerThatIsNotYetRunning() {
        when(container.isRunning()).thenReturn(false);

        runner.ensureListenersStarted();

        verify(container).start();
    }

    @Test
    void leavesAnAlreadyRunningContainerAlone() {
        when(container.isRunning()).thenReturn(true);

        runner.ensureListenersStarted();

        verify(container, never()).start();
    }

    @Test
    void aFailedStartAttemptOnOneContainerNeverPropagatesSoTheNextScheduledRetryStillFires() {
        when(container.isRunning()).thenReturn(false);
        doThrow(new IllegalStateException("Kafka unreachable")).when(container).start();

        assertThatCode(runner::ensureListenersStarted).doesNotThrowAnyException();
    }
}
