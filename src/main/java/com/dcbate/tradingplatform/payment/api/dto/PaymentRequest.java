package com.dcbate.tradingplatform.payment.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.util.UUID;

/** {@code idempotencyKey} is caller-supplied and unique-constrained — see {@code PaymentServiceImpl.submitPayment()}. */
public record PaymentRequest(
        @NotBlank String clientId,
        @NotNull UUID sourceAccountId,
        @NotNull @DecimalMin(value = "0.00000001", message = "amount must be positive") BigDecimal amount,
        @NotBlank String idempotencyKey,
        @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "country must be a 2-letter ISO code") String country) {
}
