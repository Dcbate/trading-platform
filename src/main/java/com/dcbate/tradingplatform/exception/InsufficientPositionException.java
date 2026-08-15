package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;
import java.util.UUID;

/** Thrown when a sell order's quantity exceeds what the account actually holds in that symbol; mapped to 409. */
public class InsufficientPositionException extends RuntimeException {

    public InsufficientPositionException(UUID accountId, String symbol, BigDecimal requested, BigDecimal available) {
        super("Account %s holds %s %s, cannot sell %s".formatted(accountId, available, symbol, requested));
    }
}
