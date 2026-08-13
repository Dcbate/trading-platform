package com.dcbate.tradingplatform.notification.event;

import com.dcbate.tradingplatform.kafka.event.NotificationEvent;
import com.dcbate.tradingplatform.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Delivers {@code notifications} with the spec's exponential backoff (1s, 2s, 4s, 8s, 16s — five
 * retries after the original attempt, six tries total) via Spring Kafka's built-in retry-topic
 * mechanism, not a hand-rolled loop. Exhausted retries land on {@code notifications-dlq}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventConsumer {

    private final NotificationService notificationService;
    private final ObjectMapper objectMapper;

    @RetryableTopic(
            attempts = "6",
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 16000),
            dltTopicSuffix = "-dlq",
            listenerContainerFactory = "retryableListenerFactory")
    @KafkaListener(
            topics = "${kafka.topics.notifications}",
            groupId = "notification-service",
            containerFactory = "retryableListenerFactory")
    public void onNotification(String payload) throws Exception {
        notificationService.deliver(objectMapper.readValue(payload, NotificationEvent.class));
    }

    @DltHandler
    public void onDlt(String payload) {
        try {
            notificationService.markDeadLettered(objectMapper.readValue(payload, NotificationEvent.class));
        } catch (Exception e) {
            log.error("Failed to process DLT payload, cannot mark dead-lettered: {}", e.getMessage());
        }
    }
}
