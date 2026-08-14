package com.dcbate.tradingplatform.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;

/** Published to {@code prices} on every simulated market data tick. */
public record PriceUpdateEvent(String currencyPair, BigDecimal price, Instant timestamp) {
}
