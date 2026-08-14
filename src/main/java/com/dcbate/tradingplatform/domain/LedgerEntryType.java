package com.dcbate.tradingplatform.domain;

/** One leg of a double-entry booking; every settled payment gets exactly one of each. */
public enum LedgerEntryType {
    DEBIT,
    CREDIT
}
