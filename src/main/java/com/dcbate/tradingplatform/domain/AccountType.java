package com.dcbate.tradingplatform.domain;

/**
 * What kind of account a client opened. {@code FX_TRADING} accounts are the conventional home for
 * currency-pair orders, and {@code BROKERAGE}/{@code CRYPTO} for stock and crypto orders
 * respectively — but this is a naming convention the frontend follows, not something
 * {@code ExecutionServiceImpl} actually enforces: settlement runs for *any* order that carries a
 * funded {@code accountId}, regardless of this enum value or what {@code currencyPair} the order
 * is on. See {@code docs/DESIGN_DECISIONS.md} for the gap that leaves open.
 */
public enum AccountType {
    CHECKING,
    SAVINGS,
    FX_TRADING,
    BROKERAGE,
    CRYPTO
}
