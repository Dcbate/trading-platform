package com.dcbate.tradingplatform.trading.service.matching;

import java.math.BigDecimal;

/** One fill produced by {@link OrderBook#match}, executed at the resting order's price. */
public record Match(OrderBookEntry incoming, OrderBookEntry resting, BigDecimal quantity, BigDecimal price) {
}
