package com.dcbate.tradingplatform.domain;

/** An account's standing. Only {@code ACTIVE} accounts can fund a payment or FX order. */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
