package com.dcbate.tradingplatform.loan.api.dto;

import com.dcbate.tradingplatform.domain.LoanProductType;
import java.math.BigDecimal;

/** One entry in the loan product catalog — see {@code GET /v1/loans/products}. */
public record LoanProductResponse(String code, String displayName, BigDecimal interestRateAnnualPercent, int termMonths) {

    public static LoanProductResponse from(LoanProductType type) {
        return new LoanProductResponse(type.name(), type.getDisplayName(), type.getInterestRateAnnualPercent(), type.getTermMonths());
    }
}
