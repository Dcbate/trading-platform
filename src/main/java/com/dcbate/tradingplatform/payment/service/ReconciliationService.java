package com.dcbate.tradingplatform.payment.service;

public interface ReconciliationService {

    /** Checks every cleared settlement's ledger entries net to zero; alerts on real discrepancies. */
    void reconcile();
}
