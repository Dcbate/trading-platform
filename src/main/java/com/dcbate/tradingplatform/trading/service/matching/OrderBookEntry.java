package com.dcbate.tradingplatform.trading.service.matching;

import com.dcbate.tradingplatform.domain.OrderSide;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/** A resting or incoming order as seen by the {@link OrderBook}. Mutable: quantity depletes as it fills. */
@Getter
public final class OrderBookEntry {

    private final UUID orderId;
    private final String clientId;
    private final OrderSide side;
    private final BigDecimal price;
    private final long sequence;

    @Setter
    private BigDecimal remainingQuantity;

    public OrderBookEntry(
            UUID orderId, String clientId, OrderSide side, BigDecimal price, BigDecimal quantity, long sequence) {
        this.orderId = orderId;
        this.clientId = clientId;
        this.side = side;
        this.price = price;
        this.remainingQuantity = quantity;
        this.sequence = sequence;
    }

    public boolean isFullyFilled() {
        return remainingQuantity.signum() == 0;
    }
}
