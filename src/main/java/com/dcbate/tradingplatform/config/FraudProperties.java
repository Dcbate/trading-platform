package com.dcbate.tradingplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code payment.fraud.*} — thresholds for the three rules in {@code FraudDetectionServiceImpl}. */
@ConfigurationProperties(prefix = "payment.fraud")
public record FraudProperties(
        int amountMultiplierThreshold, int maxPaymentsPerWindow, int windowSeconds, int countryChangeWindowHours) {
}
