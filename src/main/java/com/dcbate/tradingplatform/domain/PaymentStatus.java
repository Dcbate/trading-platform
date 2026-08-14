package com.dcbate.tradingplatform.domain;

/**
 * A payment's lifecycle: {@code PENDING} (accepted) &rarr; fraud check &rarr; either
 * {@code UNDER_REVIEW}/{@code BLOCKED} (Fraud Detection Service; both terminal without a
 * compliance-officer {@code approve}/{@code reject} call) or {@code RESERVED} (Settlement saga
 * begins) &rarr; {@code SETTLED}/{@code FAILED} (saga outcome). See
 * {@code docs/PAYMENT_SYSTEM.md} for the full lifecycle diagram.
 */
public enum PaymentStatus {
    PENDING,
    UNDER_REVIEW,
    BLOCKED,
    RESERVED,
    SETTLED,
    FAILED
}
