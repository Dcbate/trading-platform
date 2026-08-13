package com.dcbate.tradingplatform.config;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading")
public record TradingProperties(List<String> symbols, PriceFeed priceFeed) {

    public record PriceFeed(long tickIntervalMs, BigDecimal anomalyThresholdPercent) {
    }
}
