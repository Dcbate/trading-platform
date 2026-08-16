package com.dcbate.tradingplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Topic names bound from {@code kafka.topics.*} in application.yml — every producer/consumer in the app reads names from here rather than hardcoding strings. */
@ConfigurationProperties(prefix = "kafka.topics")
public record KafkaTopicsProperties(
        String orders,
        String ordersValidated,
        String trades,
        String prices,
        String riskAlerts,
        String payments,
        String paymentsValidated,
        String ledgerEntries,
        String fraudAlerts,
        String notifications,
        String accountActivity,
        String transfers,
        String loans) {
}
