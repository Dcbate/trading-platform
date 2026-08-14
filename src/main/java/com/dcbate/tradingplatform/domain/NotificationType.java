package com.dcbate.tradingplatform.domain;

/** What triggered a {@code NotificationEvent}; only non-{@code PAYMENT_SETTLED} outcomes get AI-summarized before delivery. */
public enum NotificationType {
    PAYMENT_SETTLED,
    PAYMENT_FAILED,
    FRAUD_ALERT,
    RECONCILIATION_ALERT
}
