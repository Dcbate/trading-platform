package com.dcbate.tradingplatform.kafka.event;

import com.dcbate.tradingplatform.domain.NotificationType;
import java.time.Instant;
import java.util.UUID;

/**
 * Published to {@code notifications} whenever a payment reaches a customer-visible outcome
 * (settled, failed, fraud-blocked, reconciliation discrepancy).
 */
public record NotificationEvent(
        UUID notificationId, UUID paymentId, String clientId, NotificationType type, String reason, Instant createdAt) {
}
