package com.dcbate.tradingplatform.loan.api.dto;

import com.dcbate.tradingplatform.domain.LoanProductType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Request to originate a loan; {@code accountId} is where the principal is disbursed to and
 * repayments are debited from. The interest rate and term aren't caller-supplied — they come from
 * the selected {@link LoanProductType} (see {@code GET /v1/loans/products} for the catalog).
 */
public record LoanRequest(
        @NotBlank String clientId,
        @NotNull UUID accountId,
        @NotNull @DecimalMin(value = "0.01", message = "principal must be positive") BigDecimal principal,
        @NotNull LoanProductType productType) {
}
