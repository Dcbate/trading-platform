package com.dcbate.tradingplatform.exception;

import com.dcbate.tradingplatform.domain.PaymentStatus;
import java.util.UUID;

public class InvalidPaymentStateException extends RuntimeException {

    public InvalidPaymentStateException(UUID paymentId, PaymentStatus expected, PaymentStatus actual) {
        super("Payment %s is %s, expected %s".formatted(paymentId, actual, expected));
    }
}
