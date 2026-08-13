package com.dcbate.tradingplatform.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.risk")
public record RiskProperties(BigDecimal maxNotionalPerClient, int maxOrdersPerWindow, int windowSeconds) {
}
