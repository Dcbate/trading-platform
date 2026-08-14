package com.dcbate.tradingplatform.domain;

/**
 * An order's lifecycle: {@code PENDING} (just accepted) &rarr; {@code VALIDATED} (passed Risk
 * Service) &rarr; {@code PARTIALLY_FILLED}/{@code FILLED} (written by Execution Service as
 * matches occur), or {@code REJECTED} (terminal, Risk Service only). See
 * {@code docs/TRADING_SYSTEM.md} for the full lifecycle diagram.
 */
public enum OrderStatus {
    PENDING,
    VALIDATED,
    PARTIALLY_FILLED,
    FILLED,
    REJECTED
}
