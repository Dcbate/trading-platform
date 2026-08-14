package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown by {@code PaymentServiceImpl.getPayment()}; mapped to 404 by {@code GlobalExceptionHandler}. */
public class PaymentNotFoundException extends RuntimeException {

    public PaymentNotFoundException(UUID paymentId) {
        super("Payment not found: " + paymentId);
    }
}
