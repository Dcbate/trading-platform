package com.dcbate.tradingplatform.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code payment.settlement.*}. {@code simulatedBankFailureThreshold} is a test seam (see {@code BankClearingClientImpl}), not a real risk limit. */
@ConfigurationProperties(prefix = "payment.settlement")
public record SettlementProperties(BigDecimal simulatedBankFailureThreshold) {
}
