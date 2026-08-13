package com.dcbate.tradingplatform.kafka.event;

import com.dcbate.tradingplatform.domain.LedgerEntryType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Published to {@code ledger-entries} for compliance archival (365-day retention) whenever a ledger row is written. */
public record LedgerEntryEvent(
        UUID entryId, UUID paymentId, String accountId, LedgerEntryType entryType, BigDecimal amount, Instant createdAt) {
}
