package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.util.UUID;

/** Thrown when a Game Mode savings withdrawal would need more than the session's current savings balance; mapped to 409. */
public class GameInsufficientSavingsException extends RuntimeException {

    public GameInsufficientSavingsException(UUID sessionId, BigDecimal requested, BigDecimal available) {
        super("Game session %s has insufficient savings: requested %s, available %s".formatted(sessionId, requested, available));
    }
}
