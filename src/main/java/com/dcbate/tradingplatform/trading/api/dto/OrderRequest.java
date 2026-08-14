package com.dcbate.tradingplatform.trading.api.dto;

import com.dcbate.tradingplatform.domain.OrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Inbound order submission — validated, then mapped to an {@code Order} by {@code OrderServiceImpl}. */
public record OrderRequest(
        @NotBlank String clientId,
        @NotBlank String symbol,
        @NotNull OrderSide side,
        @NotNull @DecimalMin(value = "0.00000001", message = "quantity must be positive") BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.00000001", message = "price must be positive") BigDecimal price) {
}
