package com.dcbate.tradingplatform.domain;

/**
 * What kind of activity happened to a client's money — the single vocabulary shared by the
 * persisted {@link Activity} audit trail and the bank statement read model. {@code FX_ORDER} and
 * {@code PAYMENT} are never persisted as an {@code Activity} row (an order/payment's lifecycle is
 * still owned by its own mutable aggregate — see {@code BankStatementServiceImpl}'s javadoc for
 * why); every other value is written by the domain service that causes it, at the moment it
 * happens, and never updated afterward. {@code CRYPTO_BUY}/{@code CRYPTO_SELL} are the one
 * exception to "written at the moment it happens, not derived" being an order-lifecycle thing —
 * a crypto trade settles instantly (see {@code CryptoTradeServiceImpl}), so the Activity row
 * *is* the record of the trade, there's no separate order entity behind it.
 */
public enum ActivityType {
    FX_ORDER,
    PAYMENT,
    TRANSFER_OUT,
    TRANSFER_IN,
    DEPOSIT,
    WITHDRAWAL,
    CONVERSION,
    ACCOUNT_CLOSURE,
    LOAN_ORIGINATED,
    LOAN_REPAYMENT,
    CRYPTO_BUY,
    CRYPTO_SELL
}
