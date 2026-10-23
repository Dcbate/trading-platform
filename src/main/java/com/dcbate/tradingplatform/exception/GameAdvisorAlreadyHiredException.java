package com.dcbate.tradingplatform.exception;

/**
 * Thrown when a Game Mode session tries to hire an accountant it's already hired; mapped to 409.
 * Player-facing message — see {@link GameInsufficientFundsException}'s javadoc for why (no
 * session UUID, plain English).
 */
public class GameAdvisorAlreadyHiredException extends RuntimeException {

    public GameAdvisorAlreadyHiredException() {
        super("You've already hired an accountant this session — one hire is all you get.");
    }
}
