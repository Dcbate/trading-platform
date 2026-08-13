package com.dcbate.tradingplatform.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published to {@code payments-validated} once the Fraud Detection Service clears a payment. */
public record PaymentValidatedEvent(
        UUID paymentId, String clientId, BigDecimal amount, String country, Instant createdAt) {
}
