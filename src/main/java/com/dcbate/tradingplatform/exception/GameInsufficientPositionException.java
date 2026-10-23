package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Thrown when a Game Mode sell's quantity exceeds what the session actually holds in that symbol;
 * mapped to 409. Player-facing message — see {@link GameInsufficientFundsException}'s javadoc for
 * why (no session UUID, rounded, plain English).
 */
public class GameInsufficientPositionException extends RuntimeException {

    public GameInsufficientPositionException(String symbol, BigDecimal requested, BigDecimal available) {
        super("You only hold %s of %s, so you can't sell %s.".formatted(quantity(available), symbol, quantity(requested)));
    }

    private static String quantity(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
