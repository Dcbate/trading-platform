package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.domain.Payment;

public interface LedgerService {

    /** Records one DEBIT + one CREDIT row for the payment, atomically, and archives both to Kafka. */
    void recordDoubleEntry(Payment payment);

    /**
     * Writes reversing entries (opposite DEBIT/CREDIT, same accounts and amount) for every
     * existing entry on the payment. Never edits or deletes the originals — the ledger stays
     * append-only.
     */
    void reverseEntries(Payment payment);
}
