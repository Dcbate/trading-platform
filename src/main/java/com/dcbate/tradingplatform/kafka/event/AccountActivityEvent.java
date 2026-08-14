package com.dcbate.tradingplatform.kafka.event;

import com.dcbate.tradingplatform.domain.AccountActivityType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Published to {@code account-activity} for deposits, withdrawals, and FX conversions — the
 * audit trail for balance changes that don't go through a {@code Payment} or {@code Transfer}.
 * {@code relatedAccountId}/{@code rate} are only populated for {@code CONVERSION}.
 */
public record AccountActivityEvent(
        UUID accountId,
        String clientId,
        AccountActivityType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        UUID relatedAccountId,
        BigDecimal rate,
        Instant occurredAt) {
}
