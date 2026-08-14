package com.dcbate.tradingplatform.account.api.dto;

import com.dcbate.tradingplatform.domain.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

/** Request to open a new account; {@code openingBalance} may be zero but not negative. */
public record AccountRequest(
        @NotBlank String clientId,
        @NotNull AccountType accountType,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO code") String currency,
        @NotNull @DecimalMin(value = "0", message = "opening balance cannot be negative") BigDecimal openingBalance) {
}
