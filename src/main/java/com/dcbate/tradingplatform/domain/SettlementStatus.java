package com.dcbate.tradingplatform.domain;

/** Progress of one settlement saga attempt: reserved and running, cleared with the bank, or compensated (reversed) after a clearing failure. */
public enum SettlementStatus {
    IN_PROGRESS,
    CLEARED,
    COMPENSATED
}
