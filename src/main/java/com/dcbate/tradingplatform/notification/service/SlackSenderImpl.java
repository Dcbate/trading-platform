package com.dcbate.tradingplatform.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Stand-in for a real Slack webhook integration — none configured for Phase 2. */
@Slf4j
@Component
public class SlackSenderImpl implements SlackSender {

    @Override
    public void send(String clientId, String subject, String body) {
        log.info("[SLACK stand-in] channel=ops, client={}, subject=\"{}\", body=\"{}\"", clientId, subject, body);
    }
}
