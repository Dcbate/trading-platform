package com.dcbate.tradingplatform.trading.api.dto;

import java.math.BigDecimal;

/** One currency pair's latest cached price — see {@code PriceFeedServiceImpl}'s synthetic feed. */
public record PriceResponse(String currencyPair, BigDecimal price) {
}
