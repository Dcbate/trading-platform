package com.dcbate.tradingplatform.loan.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request to originate a loan; {@code accountId} is where the principal is disbursed to and repayments are debited from. */
public record LoanRequest(
        @NotBlank String clientId,
        @NotNull UUID accountId,
        @NotNull @DecimalMin(value = "0.01", message = "principal must be positive") BigDecimal principal,
        @NotNull @DecimalMin(value = "0", message = "interest rate cannot be negative") BigDecimal interestRateAnnualPercent) {
}
