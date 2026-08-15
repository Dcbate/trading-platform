package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown when a Game Mode loan id doesn't resolve to a row, or doesn't belong to the given session; mapped to 404. */
public class GameLoanNotFoundException extends RuntimeException {

    public GameLoanNotFoundException(UUID gameLoanId) {
        super("Game loan not found: " + gameLoanId);
    }
}
