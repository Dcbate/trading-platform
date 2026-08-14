package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown by {@code PaymentServiceImpl} when a payment's source account is {@code FROZEN} or {@code CLOSED}. */
public class AccountNotActiveException extends RuntimeException {

    public AccountNotActiveException(UUID accountId) {
        super("Account not active: " + accountId);
    }
}
