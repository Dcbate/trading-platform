package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown when a loan/trade is attempted against a session that's already won, lost to time, or gone bankrupt; mapped to 409. */
public class GameSessionNotActiveException extends RuntimeException {

    public GameSessionNotActiveException(UUID sessionId) {
        super("Game session %s has already ended".formatted(sessionId));
    }
}
