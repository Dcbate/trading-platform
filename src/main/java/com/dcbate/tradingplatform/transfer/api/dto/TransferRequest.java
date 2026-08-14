package com.dcbate.tradingplatform.transfer.api.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/** Request to move money between two clients' accounts at this bank — see {@code TransferServiceImpl}. */
public record TransferRequest(
        @NotNull UUID fromAccountId,
        @NotNull UUID toAccountId,
        @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount) {
}
