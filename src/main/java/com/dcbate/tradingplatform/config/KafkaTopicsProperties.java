package com.dcbate.tradingplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kafka.topics")
public record KafkaTopicsProperties(
        String orders, String ordersValidated, String trades, String prices, String riskAlerts) {
}
