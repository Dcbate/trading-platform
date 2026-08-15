package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.util.UUID;

/** Thrown when a Game Mode buy/loan action would need more cash than the session currently has; mapped to 409. */
public class GameInsufficientFundsException extends RuntimeException {

    public GameInsufficientFundsException(UUID sessionId, BigDecimal requested, BigDecimal available) {
        super("Game session %s has insufficient cash: requested %s, available %s".formatted(sessionId, requested, available));
    }
}
