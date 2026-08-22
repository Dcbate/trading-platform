package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown when a Game Mode position that's already insured tries to buy another policy; mapped to 409. */
public class GamePositionAlreadyInsuredException extends RuntimeException {

    public GamePositionAlreadyInsuredException(UUID sessionId, String symbol) {
        super("Game session %s already has insurance on %s".formatted(sessionId, symbol));
    }
}
