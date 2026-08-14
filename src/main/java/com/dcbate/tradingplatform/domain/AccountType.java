package com.dcbate.tradingplatform.domain;

/** What kind of account a client opened. {@code FX_TRADING} accounts are what fund/receive the FX Trading Desk's orders. */
public enum AccountType {
    CHECKING,
    SAVINGS,
    FX_TRADING
}
