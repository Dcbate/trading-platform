package com.dcbate.tradingplatform.exception;

/** Thrown by {@code AccountServiceImpl.convert} when no cached FX rate exists for the requested currency pair. */
public class RateUnavailableException extends RuntimeException {

    public RateUnavailableException(String fromCurrency, String toCurrency) {
        super("No FX rate available for %s/%s".formatted(fromCurrency, toCurrency));
    }
}
