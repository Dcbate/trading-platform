package com.dcbate.tradingplatform.exception;

import java.math.BigDecimal;

/** Thrown when a stock order's quantity isn't a whole number of shares; mapped to 400. FX orders aren't affected — a currency amount is legitimately fractional. */
public class InvalidOrderQuantityException extends RuntimeException {

    public InvalidOrderQuantityException(String symbol, BigDecimal quantity) {
        super("%s is a stock — shares must be a whole number, got %s".formatted(symbol, quantity));
    }
}
