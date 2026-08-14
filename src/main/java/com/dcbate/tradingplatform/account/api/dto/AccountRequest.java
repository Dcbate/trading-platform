package com.dcbate.tradingplatform.account.api.dto;

import com.dcbate.tradingplatform.domain.AccountType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Request to open a new account; {@code openingBalance} may be zero but not negative.
 * {@code nickname} is optional — nothing stops a client from opening two accounts of the same
 * type and currency, so it's the only way to tell them apart besides the account id.
 */
public record AccountRequest(
        @NotBlank String clientId,
        @NotNull AccountType accountType,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO code") String currency,
        @Size(max = 64, message = "nickname must be 64 characters or fewer") String nickname,
        @NotNull @DecimalMin(value = "0", message = "opening balance cannot be negative") BigDecimal openingBalance) {
}
