package com.dcbate.tradingplatform.trading.api.dto;

import com.dcbate.tradingplatform.domain.Order;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** API-facing view of an {@code Order}; also what {@code OrderStreamHandler} broadcasts over WebSocket. */
public record OrderResponse(
        UUID orderId,
        String clientId,
        String currencyPair,
        OrderSide side,
        BigDecimal quantity,
        BigDecimal price,
        OrderStatus status,
        Instant createdAt,
        Instant filledAt) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getOrderId(),
                order.getClientId(),
                order.getCurrencyPair(),
                order.getSide(),
                order.getQuantity(),
                order.getPrice(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getFilledAt());
    }
}
