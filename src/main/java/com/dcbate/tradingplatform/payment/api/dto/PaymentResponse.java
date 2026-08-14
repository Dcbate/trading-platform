package com.dcbate.tradingplatform.payment.api.dto;

import com.dcbate.tradingplatform.domain.Payment;
import com.dcbate.tradingplatform.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** API-facing view of a {@code Payment}; never exposes internal fields like {@code idempotencyKey}. */
public record PaymentResponse(
        UUID paymentId,
        String clientId,
        UUID sourceAccountId,
        BigDecimal amount,
        String country,
        PaymentStatus status,
        Instant createdAt) {

    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getPaymentId(),
                payment.getClientId(),
                payment.getSourceAccountId(),
                payment.getAmount(),
                payment.getCountry(),
                payment.getStatus(),
                payment.getCreatedAt());
    }
}
