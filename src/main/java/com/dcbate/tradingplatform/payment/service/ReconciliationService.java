package com.dcbate.tradingplatform.payment.service;

/** Run on a schedule by {@code ReconciliationScheduler}; can also be invoked directly for tests or an ad-hoc run. */
public interface ReconciliationService {

    /** Checks every cleared settlement's ledger entries net to zero; alerts on real discrepancies. */
    void reconcile();
}
