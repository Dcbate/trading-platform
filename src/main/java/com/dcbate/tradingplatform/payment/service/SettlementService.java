package com.dcbate.tradingplatform.payment.service;

import com.dcbate.tradingplatform.kafka.event.PaymentValidatedEvent;

/** The settlement saga's single entry point, driven by {@code SettlementEventConsumer}. */
public interface SettlementService {

    /** Orchestrates the reserve -> ledger -> clear saga, compensating on a clearing failure. */
    void process(PaymentValidatedEvent event);
}
