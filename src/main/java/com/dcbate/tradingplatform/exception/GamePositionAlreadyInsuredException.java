package com.dcbate.tradingplatform.exception;

/**
 * Thrown when a Game Mode position that's already insured tries to buy another policy; mapped to
 * 409. Player-facing message — see {@link GameInsufficientFundsException}'s javadoc for why (no
 * session UUID, plain English).
 */
public class GamePositionAlreadyInsuredException extends RuntimeException {

    public GamePositionAlreadyInsuredException(String symbol) {
        super("Your %s position is already insured — one policy per position.".formatted(symbol));
    }
}
