package com.dcbate.tradingplatform.game.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** If {@code amount} exceeds what's actually owed, only what's owed is taken — same rule the real loan repayment uses. */
public record GameLoanRepayRequest(@NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount) {
}
