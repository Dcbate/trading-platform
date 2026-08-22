package com.dcbate.tradingplatform.exception;

import java.time.Instant;
import java.util.UUID;

/** Thrown when a Game Mode session tries to activate a speed boost before its cooldown has elapsed; mapped to 409. */
public class GameSpeedBoostOnCooldownException extends RuntimeException {

    public GameSpeedBoostOnCooldownException(UUID sessionId, Instant availableAt) {
        super("Game session %s speed boost is on cooldown until %s".formatted(sessionId, availableAt));
    }
}
