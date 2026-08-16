package com.dcbate.tradingplatform.statement.api.dto;

/** What kind of activity a {@link BankStatementEntry} represents — one unified feed across every domain that moves a client's money or sits on the FX desk. */
public enum StatementEntryType {
    FX_ORDER,
    PAYMENT,
    TRANSFER_OUT,
    TRANSFER_IN,
    DEPOSIT,
    WITHDRAWAL,
    CONVERSION,
    ACCOUNT_CLOSURE,
    LOAN_ORIGINATED,
    LOAN_REPAYMENT
}
