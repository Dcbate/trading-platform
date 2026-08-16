package com.dcbate.tradingplatform.notification.service;

/** {@code EmailSenderImpl} is the only implementation — an always-succeeds logging stand-in, since no real SendGrid/SMTP provider is configured yet. */
public interface EmailSender {

    void send(String clientId, String subject, String body);
}
