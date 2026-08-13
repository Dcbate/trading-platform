package com.dcbate.tradingplatform.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.reconciliation")
public record ReconciliationProperties(BigDecimal autoResolveThreshold, String scheduleCron) {
}
