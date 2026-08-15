package com.dcbate.tradingplatform.domain;

import java.math.BigDecimal;
import lombok.Getter;

/**
 * The bank's fixed catalog of loan products a client originates against — rate and term come
 * from the product, not from a value the caller types in, the same way a real bank prices a loan
 * by product rather than letting a customer name their own rate. Both are snapshotted onto the
 * {@code Loan} row at origination ({@code LoanServiceImpl.originate}), so a later change to this
 * catalog never retroactively alters an already-originated loan.
 */
@Getter
public enum LoanProductType {
    PERSONAL_SHORT("Personal Loan (1 year)", new BigDecimal("9.99"), 12),
    PERSONAL_LONG("Personal Loan (3 years)", new BigDecimal("7.49"), 36),
    AUTO("Car Loan (5 years)", new BigDecimal("5.25"), 60),
    STUDENT("Student Loan (10 years)", new BigDecimal("4.25"), 120),
    MORTGAGE("Mortgage (30 years)", new BigDecimal("3.75"), 360);

    private final String displayName;
    private final BigDecimal interestRateAnnualPercent;
    private final int termMonths;

    LoanProductType(String displayName, BigDecimal interestRateAnnualPercent, int termMonths) {
        this.displayName = displayName;
        this.interestRateAnnualPercent = interestRateAnnualPercent;
        this.termMonths = termMonths;
    }
}
