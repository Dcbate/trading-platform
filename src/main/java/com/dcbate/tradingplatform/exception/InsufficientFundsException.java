package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.util.UUID;

/** Thrown by {@code PaymentServiceImpl} when a payment's amount exceeds its source account's balance. */
public class InsufficientFundsException extends RuntimeException {

    public InsufficientFundsException(UUID accountId, BigDecimal requested, BigDecimal available) {
        super("Account %s has insufficient funds: requested %s, available %s".formatted(accountId, requested, available));
    }
}
