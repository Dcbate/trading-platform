package com.dcbate.tradingplatform.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Stand-in for a real email provider (SendGrid etc.) — none configured for Phase 2. */
@Slf4j
@Component
public class EmailSenderImpl implements EmailSender {

    @Override
    public void send(String clientId, String subject, String body) {
        log.info("[EMAIL stand-in] to={}, subject=\"{}\", body=\"{}\"", clientId, subject, body);
    }
}
