package com.dcbate.tradingplatform.notification.service;

public interface SlackSender {

    void send(String clientId, String subject, String body);
}
