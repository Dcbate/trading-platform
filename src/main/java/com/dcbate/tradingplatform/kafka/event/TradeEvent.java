package com.dcbate.tradingplatform.kafka.event;

import com.dcbate.tradingplatform.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published to {@code trades} by the Matching Engine. Carries the resulting status of both
 * involved orders so the Execution Service can apply it without recomputing fill state.
 */
public record TradeEvent(
        UUID tradeId,
        UUID buyOrderId,
        UUID sellOrderId,
        String currencyPair,
        BigDecimal quantity,
        BigDecimal price,
        OrderStatus buyOrderStatus,
        OrderStatus sellOrderStatus,
        Instant createdAt) {
}
