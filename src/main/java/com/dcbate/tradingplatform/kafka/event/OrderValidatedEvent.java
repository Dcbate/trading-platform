package com.dcbate.tradingplatform.kafka.event;

import com.dcbate.tradingplatform.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published to {@code orders-validated} once the Risk Service clears an order to trade. */
public record OrderValidatedEvent(
        UUID orderId,
        String clientId,
        String symbol,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal price,
        Instant createdAt) {
}
