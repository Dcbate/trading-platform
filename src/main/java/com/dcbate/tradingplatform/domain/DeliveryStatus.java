package com.dcbate.tradingplatform.domain;

/**
 * A {@code Notification} row is only ever persisted as {@code SENT} (success) or
 * {@code DEAD_LETTERED} (every {@code @RetryableTopic} retry exhausted) — see
 * {@code NotificationServiceImpl}'s javadoc for why nothing is written in between.
 */
public enum DeliveryStatus {
    PENDING,
    SENT,
    FAILED,
    DEAD_LETTERED
}
