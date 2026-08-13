package com.dcbate.tradingplatform.trading.api.dto;

import com.dcbate.tradingplatform.domain.OrderSide;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record OrderRequest(
        @NotBlank String clientId,
        @NotBlank String symbol,
        @NotNull OrderSide side,
        @NotNull @DecimalMin(value = "0.00000001", message = "quantity must be positive") BigDecimal quantity,
        @NotNull @DecimalMin(value = "0.00000001", message = "price must be positive") BigDecimal price) {
}
