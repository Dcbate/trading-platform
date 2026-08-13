package com.dcbate.tradingplatform.kafka.event;

import com.dcbate.tradingplatform.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published to the {@code orders} topic when an order is first accepted. */
public record OrderEvent(
        UUID orderId,
        String clientId,
        String symbol,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal price,
        Instant createdAt) {
}
