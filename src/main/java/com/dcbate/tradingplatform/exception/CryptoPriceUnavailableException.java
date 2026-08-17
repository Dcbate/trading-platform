package com.dcbate.tradingplatform.exception;

/** Thrown by {@code CryptoTradeServiceImpl} when no cached price exists yet for a crypto pair; mapped to 409. */
public class CryptoPriceUnavailableException extends RuntimeException {

    public CryptoPriceUnavailableException(String symbol) {
        super("No live price available for %s".formatted(symbol));
    }
}
