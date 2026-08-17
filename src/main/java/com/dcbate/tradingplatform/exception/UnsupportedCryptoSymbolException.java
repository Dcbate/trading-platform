package com.dcbate.tradingplatform.exception;

/** Thrown when a crypto trade requests a symbol the bank doesn't offer; mapped to 400. */
public class UnsupportedCryptoSymbolException extends RuntimeException {

    public UnsupportedCryptoSymbolException(String symbol) {
        super("Not a supported crypto pair: %s".formatted(symbol));
    }
}
