package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.OrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Fills instantly at the current simulated market price — no order book, no resting orders. */
public record GameTradeRequest(
        @NotBlank String symbol,
        @NotNull OrderSide side,
        @NotNull @DecimalMin(value = "0.00000001", message = "quantity must be positive") BigDecimal quantity) {
}
