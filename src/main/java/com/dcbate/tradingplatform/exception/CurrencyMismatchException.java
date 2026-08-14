package com.dcbate.tradingplatform.exception;

/** Thrown by {@code TransferServiceImpl} when the source and destination accounts hold different currencies. */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String fromCurrency, String toCurrency) {
        super("Cannot transfer directly between %s and %s accounts; convert first".formatted(fromCurrency, toCurrency));
    }
}
