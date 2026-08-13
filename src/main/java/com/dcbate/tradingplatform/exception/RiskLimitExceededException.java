package com.dcbate.tradingplatform.exception;

public class RiskLimitExceededException extends RuntimeException {

    public RiskLimitExceededException(String message) {
        super(message);
    }
}
