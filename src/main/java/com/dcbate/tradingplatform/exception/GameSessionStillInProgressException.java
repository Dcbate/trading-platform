package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown when a debrief is requested for a session that hasn't ended yet; mapped to 409. */
public class GameSessionStillInProgressException extends RuntimeException {

    public GameSessionStillInProgressException(UUID sessionId) {
        super("Game session %s is still in progress; a debrief is only available once it has ended".formatted(sessionId));
    }
}
