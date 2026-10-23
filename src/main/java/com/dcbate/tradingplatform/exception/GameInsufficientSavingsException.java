package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Thrown when a Game Mode savings withdrawal would need more than the session's current savings
 * balance; mapped to 409. Player-facing message — see {@link GameInsufficientFundsException}'s
 * javadoc for why (no session UUID, rounded to pence, shown in a modal not a toast).
 */
public class GameInsufficientSavingsException extends RuntimeException {

    public GameInsufficientSavingsException(BigDecimal requested, BigDecimal available) {
        super("You don't have that much in savings — trying to move £%s, but only £%s is there."
                .formatted(money(requested), money(available)));
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
