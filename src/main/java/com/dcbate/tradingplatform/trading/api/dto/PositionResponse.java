package com.dcbate.tradingplatform.trading.api.dto;

import com.dcbate.tradingplatform.domain.Position;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** API-facing view of a {@code Position} — real quantity and real weighted-average cost basis, nothing derived/fabricated. */
public record PositionResponse(
        UUID positionId, UUID accountId, String clientId, String symbol, BigDecimal quantity, BigDecimal avgCost, Instant updatedAt) {

    public static PositionResponse from(Position position) {
        return new PositionResponse(
                position.getPositionId(),
                position.getAccountId(),
                position.getClientId(),
                position.getSymbol(),
                position.getQuantity(),
                position.getAvgCost(),
                position.getUpdatedAt());
    }
}
