package com.dcbate.tradingplatform.notification.service;

/** Implementations: {@code LoggingEmailSender} (default, always-succeeds stand-in) and {@code SendGridEmailSender} (real API, falls back to logging). */
public interface EmailSender {

    void send(String clientId, String subject, String body);
}
