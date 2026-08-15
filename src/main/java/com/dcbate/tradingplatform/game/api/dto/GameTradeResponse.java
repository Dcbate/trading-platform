package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record GameTradeResponse(
        UUID tradeId, String symbol, OrderSide side, BigDecimal quantity, BigDecimal price,
        BigDecimal fee, BigDecimal realizedPnl, Instant createdAt) {
}
