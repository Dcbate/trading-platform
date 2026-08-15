package com.dcbate.tradingplatform.trading.api.dto;

import java.math.BigDecimal;

/** One instrument's latest cached price — a currency pair or a stock symbol; see {@code PriceFeedServiceImpl}'s synthetic feed. */
public record PriceResponse(String symbol, BigDecimal price) {
}
