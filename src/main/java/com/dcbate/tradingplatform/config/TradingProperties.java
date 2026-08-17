package com.dcbate.tradingplatform.config;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bound from {@code trading.*} (excluding the {@code trading.risk} and {@code trading.matching-engine} sub-trees, which have their own records). */
@ConfigurationProperties(prefix = "trading")
public record TradingProperties(
        List<String> currencyPairs, List<String> stockSymbols, List<String> cryptoSymbols, PriceFeed priceFeed) {

    public record PriceFeed(long tickIntervalMs, BigDecimal anomalyThresholdPercent) {
    }
}
