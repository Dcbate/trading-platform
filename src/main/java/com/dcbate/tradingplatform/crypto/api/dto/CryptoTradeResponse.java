package com.dcbate.tradingplatform.crypto.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The result of an instantly-settled crypto trade — everything already happened by the time this
 * is returned, there's no pending/resting state the way an {@code Order} has.
 */
public record CryptoTradeResponse(
        UUID accountId,
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal price,
        BigDecimal notional,
        BigDecimal balanceAfter,
        BigDecimal positionQuantityAfter,
        Instant executedAt) {
}
