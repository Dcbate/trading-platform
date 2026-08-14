package com.dcbate.tradingplatform.loan.api.dto;

import com.dcbate.tradingplatform.domain.Loan;
import com.dcbate.tradingplatform.domain.LoanStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LoanResponse(
        UUID loanId,
        String clientId,
        UUID accountId,
        BigDecimal principal,
        BigDecimal outstandingPrincipal,
        BigDecimal interestRateAnnualPercent,
        BigDecimal accruedInterest,
        LoanStatus status,
        Instant createdAt) {

    public static LoanResponse from(Loan loan) {
        return new LoanResponse(
                loan.getLoanId(),
                loan.getClientId(),
                loan.getAccountId(),
                loan.getPrincipal(),
                loan.getOutstandingPrincipal(),
                loan.getInterestRateAnnualPercent(),
                loan.getAccruedInterest(),
                loan.getStatus(),
                loan.getCreatedAt());
    }
}
