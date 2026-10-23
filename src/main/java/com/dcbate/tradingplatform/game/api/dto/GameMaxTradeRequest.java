package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.OrderSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * "Buy Max" / "Sell Max" — no {@code quantity} field on purpose. A client-computed max (cash
 * divided by a locally cached price) is always slightly wrong: it can't see the fee that gets
 * added on top of the notional, and the price itself may have moved since it was last polled. The
 * server resolves the actual max affordable (or max held, for a sell) against its own live price
 * in the same transaction that executes the trade, so this button can never fail with "insufficient
 * funds" the way a client-guessed quantity could.
 */
public record GameMaxTradeRequest(@NotBlank String symbol, @NotNull OrderSide side) {
}
