package com.dcbate.tradingplatform.exception;

/** Thrown by {@code AccountServiceImpl.closeAccount} for a missing/self-referential destination account; mapped to 409. */
public class InvalidAccountClosureException extends RuntimeException {

    public InvalidAccountClosureException(String message) {
        super(message);
    }
}
