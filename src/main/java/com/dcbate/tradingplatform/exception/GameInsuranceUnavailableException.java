package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Thrown when a Game Mode position is already trading at or below where its insurance floor would
 * sit — buying a policy at that point wouldn't be insurance, it'd be an instant, guaranteed payout
 * for the 3% premium (the floor marks the position up to 85% of avg cost immediately on purchase,
 * regardless of the real market price). Real insurance doesn't cover damage that's already
 * happened; mapped to 409.
 */
public class GameInsuranceUnavailableException extends RuntimeException {

    public GameInsuranceUnavailableException(String symbol, BigDecimal currentPrice, BigDecimal wouldBeFloor) {
        super(("Can't insure %s — it's already trading at £%s, below the £%s floor a policy would set. "
                + "Insurance only covers a drop that hasn't happened yet.")
                .formatted(symbol, money(currentPrice), money(wouldBeFloor)));
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
