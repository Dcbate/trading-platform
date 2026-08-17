package com.dcbate.tradingplatform.crypto.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/** Body for {@code POST /v1/crypto/{accountId}/buy} and {@code /sell}. */
public record CryptoTradeRequest(
        @NotBlank String symbol,
        @DecimalMin(value = "0.00000001", message = "quantity must be positive") BigDecimal quantity) {
}
