package com.dcbate.tradingplatform.exception;

import java.util.UUID;

/** Thrown by {@code LoanServiceImpl.repay} when a repayment is attempted against a loan that isn't {@code ACTIVE}. */
public class LoanNotActiveException extends RuntimeException {

    public LoanNotActiveException(UUID loanId) {
        super("Loan %s is not active".formatted(loanId));
    }
}
