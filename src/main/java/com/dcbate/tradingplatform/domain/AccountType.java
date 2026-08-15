package com.dcbate.tradingplatform.domain;

/**
 * What kind of account a client opened. {@code FX_TRADING} accounts fund/receive the FX Trading
 * Desk's currency-pair orders (fills don't move balance — see docs/DESIGN_DECISIONS.md).
 * {@code BROKERAGE} accounts fund/receive stock orders and do settle on fill: a buy debits cash
 * and credits a {@code Position}, a sell does the reverse — see {@code ExecutionServiceImpl}.
 */
public enum AccountType {
    CHECKING,
    SAVINGS,
    FX_TRADING,
    BROKERAGE
}
