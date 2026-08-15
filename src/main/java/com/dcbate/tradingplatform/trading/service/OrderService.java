package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import java.util.List;
import java.util.UUID;

/**
 * Order intake and lookup — the entry point of the trading pipeline (see docs/TRADING_SYSTEM.md).
 * A {@code TRADER}/{@code ADMIN} caller (bank staff) can act on any client's orders; a
 * {@code CLIENT} caller can only submit/view their own, enforced via {@link CallerPrincipal}.
 */
public interface OrderService {

    /** Persists the order as {@code PENDING} and publishes it to {@code orders} for the Risk Service to pick up. */
    OrderResponse submitOrder(OrderRequest request, CallerPrincipal caller);

    OrderResponse getOrder(UUID orderId, CallerPrincipal caller);

    List<OrderResponse> listOrdersForClient(String clientId, CallerPrincipal caller);
}
