package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import java.util.UUID;

/** Order intake and lookup — the entry point of the trading pipeline (see {@code docs/TRADING_SYSTEM.md}). */
public interface OrderService {

    /** Persists the order as {@code PENDING} and publishes it to {@code orders} for the Risk Service to pick up. */
    OrderResponse submitOrder(OrderRequest request);

    OrderResponse getOrder(UUID orderId);
}
