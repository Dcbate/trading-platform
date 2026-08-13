package com.dcbate.tradingplatform.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "payment.settlement")
public record SettlementProperties(BigDecimal simulatedBankFailureThreshold) {
}
