package com.dcbate.tradingplatform.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code trading.risk.*} — the two limits {@code RiskServiceImpl} checks. */
@ConfigurationProperties(prefix = "trading.risk")
public record RiskProperties(BigDecimal maxNotionalPerClient, int maxOrdersPerWindow, int windowSeconds) {
}
