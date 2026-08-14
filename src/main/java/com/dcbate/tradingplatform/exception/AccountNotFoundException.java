package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown when an account id doesn't resolve to a row (or doesn't belong to the expected client); mapped to 404 by {@code GlobalExceptionHandler}. */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID accountId) {
        super("Account not found: " + accountId);
    }
}
