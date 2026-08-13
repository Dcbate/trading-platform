package com.dcbate.tradingplatform.notification.service;

import com.dcbate.tradingplatform.ai.ClaudeSummarizer;
import com.dcbate.tradingplatform.domain.DeliveryStatus;
import com.dcbate.tradingplatform.domain.Notification;
import com.dcbate.tradingplatform.domain.NotificationType;
import com.dcbate.tradingplatform.kafka.event.NotificationEvent;
import com.dcbate.tradingplatform.notification.repository.NotificationRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A {@code Notification} row is only ever persisted on success (via {@link #deliver}) or as a
 * terminal dead-letter record (via {@link #markDeadLettered}) — a failed attempt in between
 * leaves no row, so retries never see stale state.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final ClaudeSummarizer claudeSummarizer;
    private final EmailSender emailSender;
    private final SlackSender slackSender;

    @Override
    @Transactional
    public void deliver(NotificationEvent event) {
        // Plain success needs no AI narration; failures/blocks/discrepancies are the "complex
        // scenarios" worth summarizing.
        String message = event.type() == NotificationType.PAYMENT_SETTLED
                ? event.reason()
                : claudeSummarizer.summarize(event.reason());
        String subject = subjectFor(event.type());

        emailSender.send(event.clientId(), subject, message);
        slackSender.send(event.clientId(), subject, message);

        notificationRepository.save(Notification.builder()
                .notificationId(event.notificationId())
                .paymentId(event.paymentId())
                .type(event.type())
                .deliveryStatus(DeliveryStatus.SENT)
                .retryCount(0)
                .createdAt(Instant.now())
                .build());
    }

    @Override
    @Transactional
    public void markDeadLettered(NotificationEvent event) {
        notificationRepository.save(Notification.builder()
                .notificationId(event.notificationId())
                .paymentId(event.paymentId())
                .type(event.type())
                .deliveryStatus(DeliveryStatus.DEAD_LETTERED)
                .retryCount(5)
                .createdAt(Instant.now())
                .build());
        log.error("Notification exhausted all retries and was dead-lettered: paymentId={}, type={}",
                event.paymentId(), event.type());
    }

    private String subjectFor(NotificationType type) {
        return switch (type) {
            case PAYMENT_SETTLED -> "Payment settled";
            case PAYMENT_FAILED -> "Payment failed";
            case FRAUD_ALERT -> "Payment flagged for review";
            case RECONCILIATION_ALERT -> "Reconciliation discrepancy detected";
        };
    }
}
