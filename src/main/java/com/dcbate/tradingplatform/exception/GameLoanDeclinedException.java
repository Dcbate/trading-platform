package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a Game Mode loan request would push total outstanding loan principal past what the
 * session's current net worth can support — the "credit check" a real loan would fail. Mapped to
 * 409, same as the other Game Mode conflict cases.
 */
public class GameLoanDeclinedException extends RuntimeException {

    public GameLoanDeclinedException(UUID sessionId, BigDecimal requestedTotalExposure, BigDecimal netWorth, BigDecimal maxAllowed) {
        super("Game session %s loan declined: requested total loan exposure %s exceeds %s allowed against net worth %s"
                .formatted(sessionId, requestedTotalExposure, maxAllowed, netWorth));
    }
}
