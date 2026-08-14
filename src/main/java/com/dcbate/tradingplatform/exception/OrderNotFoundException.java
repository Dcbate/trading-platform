package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown by {@code OrderServiceImpl.getOrder()}; mapped to 404 by {@code GlobalExceptionHandler}. */
public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(UUID orderId) {
        super("Order not found: " + orderId);
    }
}
