package com.dcbate.tradingplatform.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.ai.ClaudeSummarizer;
import com.dcbate.tradingplatform.domain.DeliveryStatus;
import com.dcbate.tradingplatform.domain.Notification;
import com.dcbate.tradingplatform.domain.NotificationType;
import com.dcbate.tradingplatform.kafka.event.NotificationEvent;
import com.dcbate.tradingplatform.notification.repository.NotificationRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class NotificationServiceImplTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ClaudeSummarizer claudeSummarizer;

    @Mock
    private EmailSender emailSender;

    @Mock
    private SlackSender slackSender;

    private NotificationServiceImpl notificationService;

    @BeforeEach
    void setUp() {
        notificationService = new NotificationServiceImpl(notificationRepository, claudeSummarizer, emailSender, slackSender);
    }

    private NotificationEvent event(NotificationType type) {
        return new NotificationEvent(UUID.randomUUID(), UUID.randomUUID(), "client-1", type, "something happened", Instant.now());
    }

    @Test
    void successfulDeliverySavesNotificationAsSent() {
        NotificationEvent event = event(NotificationType.PAYMENT_SETTLED);

        notificationService.deliver(event);

        verify(emailSender).send(anyString(), anyString(), anyString());
        verify(slackSender).send(anyString(), anyString(), anyString());
        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getDeliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    void plainSuccessSkipsAiSummarization() {
        notificationService.deliver(event(NotificationType.PAYMENT_SETTLED));

        verify(claudeSummarizer, never()).summarize(anyString());
    }

    @Test
    void nonSuccessOutcomeIsSummarizedByClaude() {
        when(claudeSummarizer.summarize(anyString())).thenReturn("summarized");

        notificationService.deliver(event(NotificationType.PAYMENT_FAILED));

        verify(claudeSummarizer).summarize("something happened");
        verify(emailSender).send(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("summarized"));
    }

    @Test
    void senderFailurePropagatesAndNothingIsPersisted() {
        doThrow(new RuntimeException("smtp down")).when(emailSender).send(anyString(), anyString(), anyString());

        assertThatThrownBy(() -> notificationService.deliver(event(NotificationType.PAYMENT_SETTLED)))
                .isInstanceOf(RuntimeException.class);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    void markDeadLetteredSavesTerminalStatus() {
        NotificationEvent event = event(NotificationType.PAYMENT_FAILED);

        notificationService.markDeadLettered(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());
        assertThat(captor.getValue().getDeliveryStatus()).isEqualTo(DeliveryStatus.DEAD_LETTERED);
    }
}
