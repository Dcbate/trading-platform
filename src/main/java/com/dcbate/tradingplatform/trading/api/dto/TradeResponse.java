package com.dcbate.tradingplatform.trading.api.dto;

import com.dcbate.tradingplatform.domain.Trade;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TradeResponse(
        UUID tradeId,
        UUID buyOrderId,
        UUID sellOrderId,
        String symbol,
        BigDecimal quantity,
        BigDecimal price,
        Instant createdAt) {

    public static TradeResponse from(Trade trade) {
        return new TradeResponse(
                trade.getTradeId(),
                trade.getBuyOrderId(),
                trade.getSellOrderId(),
                trade.getSymbol(),
                trade.getQuantity(),
                trade.getPrice(),
                trade.getCreatedAt());
    }
}
