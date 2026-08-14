package com.dcbate.tradingplatform.account.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Body for {@code POST /v1/accounts/{fromAccountId}/convert} — sell balance in one of the caller's own currencies to buy another. */
public record ConvertRequest(
        @NotNull UUID toAccountId,
        @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount) {
}
