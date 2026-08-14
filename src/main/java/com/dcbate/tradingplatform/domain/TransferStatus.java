package com.dcbate.tradingplatform.domain;

/** An internal (same-bank) transfer's outcome. Unlike {@code PaymentStatus} there's no in-flight saga state — the debit/credit is one atomic DB transaction, so a transfer is COMPLETED or it never happened. */
public enum TransferStatus {
    COMPLETED,
    FAILED
}
