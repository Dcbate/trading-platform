package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Thrown when a Game Mode loan request would push total outstanding loan principal past what the
 * session's current net worth can support — the "credit check" a real loan would fail. Mapped to
 * 409, same as the other Game Mode conflict cases.
 *
 * <p>The message itself is player-facing, not a log line — {@code GlobalExceptionHandler} puts
 * {@code getMessage()} straight into the API response's {@code messages} array, and the frontend
 * shows it directly (a dedicated modal, not a generic toast — see {@code LoanPanel} in
 * {@code GamePlayPage.tsx}). No session UUID and no unrounded {@code BigDecimal} scale, both of
 * which are debugging noise for a player, not useful context — real money amounts, rounded to
 * pence like everywhere else in the app, and full sentences.
 */
public class GameLoanDeclinedException extends RuntimeException {

    public GameLoanDeclinedException(BigDecimal requestedTotalExposure, BigDecimal netWorth, BigDecimal maxAllowed) {
        super("Loan declined: borrowing this much would bring your total loans to £%s, but your credit limit is £%s (1.5x your current net worth of £%s). Try a smaller amount, or pay down an existing loan first."
                .formatted(money(requestedTotalExposure), money(maxAllowed), money(netWorth)));
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
