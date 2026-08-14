package com.dcbate.tradingplatform.kafka.event;

import com.dcbate.tradingplatform.domain.TransferStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published to {@code transfers} the instant a same-bank transfer completes (or fails validation). */
public record TransferEvent(
        UUID transferId, UUID fromAccountId, UUID toAccountId, String fromClientId, String toClientId,
        BigDecimal amount, TransferStatus status, Instant createdAt) {
}
