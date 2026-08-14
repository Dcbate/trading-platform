package com.dcbate.tradingplatform.domain;

/** A loan's lifecycle: {@code ACTIVE} (disbursed, interest accruing, repayments accepted) until {@code outstandingPrincipal} and {@code accruedInterest} both reach zero, at which point it's {@code PAID_OFF}. */
public enum LoanStatus {
    ACTIVE,
    PAID_OFF
}
