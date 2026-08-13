package com.dcbate.tradingplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.fraud")
public record FraudProperties(
        int amountMultiplierThreshold, int maxPaymentsPerWindow, int windowSeconds, int countryChangeWindowHours) {
}
