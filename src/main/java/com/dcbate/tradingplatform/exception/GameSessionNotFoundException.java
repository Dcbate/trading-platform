package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown when a Game Mode session id doesn't resolve to a row; mapped to 404 by {@code GlobalExceptionHandler}. */
public class GameSessionNotFoundException extends RuntimeException {

    public GameSessionNotFoundException(UUID sessionId) {
        super("Game session not found: " + sessionId);
    }
}
