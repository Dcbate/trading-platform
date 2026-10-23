package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Thrown when a Game Mode buy/loan-repayment/savings-deposit/insurance/accountant action would
 * need more cash than the session currently has; mapped to 409.
 *
 * <p>Player-facing message, not a log line — same reasoning as {@link GameLoanDeclinedException}:
 * no session UUID, money rounded to pence, plain English. Shown in a dedicated modal rather than
 * a toast — see {@code LoanPanel}/{@code SavingsPanel} in {@code GamePlayPage.tsx}.
 */
public class GameInsufficientFundsException extends RuntimeException {

    public GameInsufficientFundsException(BigDecimal requested, BigDecimal available) {
        super("You don't have enough cash for this — it needs £%s, but you only have £%s available."
                .formatted(money(requested), money(available)));
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
