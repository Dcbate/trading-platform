package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown when a Game Mode session tries to hire a wealth manager it's already hired; mapped to 409. */
public class GameAdvisorAlreadyHiredException extends RuntimeException {

    public GameAdvisorAlreadyHiredException(UUID sessionId) {
        super("Game session %s has already hired a wealth manager".formatted(sessionId));
    }
}
