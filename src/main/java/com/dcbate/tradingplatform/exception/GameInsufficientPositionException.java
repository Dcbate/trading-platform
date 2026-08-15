package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.util.UUID;

/** Thrown when a Game Mode sell's quantity exceeds what the session actually holds in that symbol; mapped to 409. */
public class GameInsufficientPositionException extends RuntimeException {

    public GameInsufficientPositionException(UUID sessionId, String symbol, BigDecimal requested, BigDecimal available) {
        super("Game session %s holds %s %s, cannot sell %s".formatted(sessionId, available, symbol, requested));
    }
}
