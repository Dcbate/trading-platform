package com.dcbate.tradingplatform.game.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record GameSavingsRequest(@NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount) {
}
