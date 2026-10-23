package com.dcbate.tradingplatform.exception;

import java.time.Duration;
import java.time.Instant;

/**
 * Thrown when a Game Mode session tries to activate a speed boost before its cooldown has
 * elapsed; mapped to 409. Player-facing message — see {@link GameInsufficientFundsException}'s
 * javadoc for why (no session UUID, plain English).
 */
public class GameSpeedBoostOnCooldownException extends RuntimeException {

    public GameSpeedBoostOnCooldownException(Instant availableAt) {
        super("The speed boost is still cooling down — try again in %ds.".formatted(secondsUntil(availableAt)));
    }

    private static long secondsUntil(Instant availableAt) {
        long seconds = Duration.between(Instant.now(), availableAt).toSeconds();
        return Math.max(0, seconds);
    }
}
