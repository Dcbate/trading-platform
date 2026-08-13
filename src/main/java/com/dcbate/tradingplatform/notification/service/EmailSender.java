package com.dcbate.tradingplatform.notification.service;

public interface EmailSender {

    void send(String clientId, String subject, String body);
}
