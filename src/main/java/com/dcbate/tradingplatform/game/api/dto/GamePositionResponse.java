package com.dcbate.tradingplatform.game.api.dto;

import java.math.BigDecimal;

public record GamePositionResponse(
        String symbol, BigDecimal quantity, BigDecimal avgCost, BigDecimal currentPrice, BigDecimal marketValue, BigDecimal unrealizedPnl) {
}
