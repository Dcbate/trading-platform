package com.dcbate.tradingplatform.loan.api.dto;

import com.dcbate.tradingplatform.domain.Loan;
import com.dcbate.tradingplatform.domain.LoanProductType;
import com.dcbate.tradingplatform.domain.LoanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** API-facing view of a {@code Loan} — {@code interestRateAnnualPercent}/{@code termMonths} are the values snapshotted from {@code productType} at origination, not live catalog values. */
public record LoanResponse(
        UUID loanId,
        String clientId,
        UUID accountId,
        LoanProductType productType,
        BigDecimal principal,
        BigDecimal outstandingPrincipal,
        BigDecimal interestRateAnnualPercent,
        int termMonths,
        BigDecimal accruedInterest,
        LoanStatus status,
        Instant createdAt) {

    public static LoanResponse from(Loan loan) {
        return new LoanResponse(
                loan.getLoanId(),
                loan.getClientId(),
                loan.getAccountId(),
                loan.getProductType(),
                loan.getPrincipal(),
                loan.getOutstandingPrincipal(),
                loan.getInterestRateAnnualPercent(),
                loan.getTermMonths(),
                loan.getAccruedInterest(),
                loan.getStatus(),
                loan.getCreatedAt());
    }
}
