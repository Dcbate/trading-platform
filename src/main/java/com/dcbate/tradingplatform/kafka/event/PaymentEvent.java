package com.dcbate.tradingplatform.kafka.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published to {@code payments} the instant a payment is accepted. */
public record PaymentEvent(
        UUID paymentId, String clientId, BigDecimal amount, String country, Instant createdAt) {
}
