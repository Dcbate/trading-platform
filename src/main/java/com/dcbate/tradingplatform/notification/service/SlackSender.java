package com.dcbate.tradingplatform.notification.service;

/** {@code SlackSenderImpl} is the only implementation — an always-succeeds logging stand-in, since no real Slack webhook is configured yet. */
public interface SlackSender {

    void send(String clientId, String subject, String body);
}
