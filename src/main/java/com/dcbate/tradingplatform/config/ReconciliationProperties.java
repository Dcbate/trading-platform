package com.dcbate.tradingplatform.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code payment.reconciliation.*}. {@code autoResolveThreshold} is the max discrepancy that's logged and ignored rather than alerted on. */
@ConfigurationProperties(prefix = "payment.reconciliation")
public record ReconciliationProperties(BigDecimal autoResolveThreshold, String scheduleCron) {
}
