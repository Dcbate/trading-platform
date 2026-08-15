package com.dcbate.tradingplatform.game.api.dto;

import java.math.BigDecimal;

public record GamePriceResponse(String symbol, BigDecimal price) {
}
