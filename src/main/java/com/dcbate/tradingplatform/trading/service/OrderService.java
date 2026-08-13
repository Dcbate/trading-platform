package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import java.util.UUID;

public interface OrderService {

    OrderResponse submitOrder(OrderRequest request);

    OrderResponse getOrder(UUID orderId);
}
