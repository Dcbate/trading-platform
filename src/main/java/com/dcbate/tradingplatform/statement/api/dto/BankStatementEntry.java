package com.dcbate.tradingplatform.statement.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a client's bank statement. {@code amount} is signed (positive = money in, negative
 * = money out) and {@code null} for an {@link StatementEntryType#FX_ORDER} — the Matching Engine
 * settles fills against {@code Account.balance} at the individual-trade level (see
 * {@code ExecutionServiceImpl}), and reconstructing the exact settled notional for a
 * partially-filled order here would mean re-deriving it from {@code Trade} rows rather than
 * showing what's already a genuine, queryable {@code Order} row — a known simplification, not a
 * bug (see {@code docs/KNOWN_GAPS.md}).
 */
public record BankStatementEntry(
        Instant occurredAt, StatementEntryType type, String description, BigDecimal amount, UUID reference) {
}
