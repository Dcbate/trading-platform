package com.dcbate.tradingplatform.notification.service;

import com.dcbate.tradingplatform.kafka.event.NotificationEvent;

/** Driven by {@code NotificationEventConsumer}'s {@code @RetryableTopic}; see that class for the retry/DLQ mechanics. */
public interface NotificationService {

    /** Sends the notification; throws if delivery fails so the caller's retry mechanism can act. */
    void deliver(NotificationEvent event);

    /** Records that every retry was exhausted — the DLQ terminal case, for manual review. */
    void markDeadLettered(NotificationEvent event);
}
