package com.dcbate.tradingplatform.notification.service;

/** Implementations: {@code LoggingSlackSender} (default, always-succeeds stand-in) and {@code SlackWebhookSender} (real webhook, falls back to logging). */
public interface SlackSender {

    void send(String clientId, String subject, String body);
}
