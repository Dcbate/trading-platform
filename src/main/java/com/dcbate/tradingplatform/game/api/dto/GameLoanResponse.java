package com.dcbate.tradingplatform.game.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record GameLoanResponse(
        UUID gameLoanId, BigDecimal principal, BigDecimal interestOwed, BigDecimal rateAnnualPercent, Instant originatedAt) {
}
