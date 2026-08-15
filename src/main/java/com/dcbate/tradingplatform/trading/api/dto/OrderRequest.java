package com.dcbate.tradingplatform.trading.api.dto;

import com.dcbate.tradingplatform.domain.OrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Inbound order submission — validated, then mapped to an {@code Order} by {@code OrderServiceImpl}.
 * {@code accountId} is optional: omit it for a dealer-desk FX order (no settlement, existing
 * behavior), set it for a client stock order (real cash/position settlement on fill).
 */
public record OrderRequest(
        @NotBlank String clientId,
        @NotBlank String currencyPair,
        @NotNull OrderSide side,
        @NotNull @DecimalMin(value = "0.00000001", message = "quantity must be positive") BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.00000001", message = "price must be positive") BigDecimal price,
        UUID accountId) {
}
