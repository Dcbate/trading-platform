package com.dcbate.tradingplatform.account.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Body for deposit/withdraw calls on {@code AccountController}. */
public record AccountTransactionRequest(
        @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount) {
}
