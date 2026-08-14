package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown when a loan id doesn't resolve to a row; mapped to 404 by {@code GlobalExceptionHandler}. */
public class LoanNotFoundException extends RuntimeException {

    public LoanNotFoundException(UUID loanId) {
        super("Loan not found: " + loanId);
    }
}
