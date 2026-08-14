package com.dcbate.tradingplatform.exception;

import com.dcbate.tradingplatform.domain.PaymentStatus;
import java.util.UUID;

/** Thrown by the compliance {@code approve}/{@code reject} calls when a payment isn't {@code UNDER_REVIEW}; mapped to 409 by {@code GlobalExceptionHandler}. */
public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(UUID paymentId, PaymentStatus expected, PaymentStatus actual) {
        super("Payment %s is %s, expected %s".formatted(paymentId, actual, expected));
    }
}
