package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.OrderSide;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Fills instantly at the current simulated market price — no order book, no resting orders.
 * {@code quantity} is a whole number for every symbol Game Mode trades, FX pairs included — a
 * simplification the real FX desk doesn't make (a real currency amount is legitimately
 * fractional), but Game Mode is a quick, casual sandbox where "buy 1000 EUR" reads more naturally
 * than "buy 1000.4739 EUR."
 */
public record GameTradeRequest(
        @NotBlank String symbol,
        @NotNull OrderSide side,
        @NotNull @Min(value = 1, message = "quantity must be a whole number of at least 1")
        @Digits(integer = 15, fraction = 0, message = "quantity must be a whole number") BigDecimal quantity) {
}
