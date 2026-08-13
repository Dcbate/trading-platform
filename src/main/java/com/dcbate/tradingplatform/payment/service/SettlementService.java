package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.kafka.event.PaymentValidatedEvent;

public interface SettlementService {

    /** Orchestrates the reserve -> ledger -> clear saga, compensating on a clearing failure. */
    void process(PaymentValidatedEvent event);
}
