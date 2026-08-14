package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.kafka.event.OrderEvent;

/** Notional and velocity limit checks between order intake and the matching engine — see {@code RiskServiceImpl}. */
public interface RiskService {

    /** Checks the order against notional and velocity limits, then routes it accordingly. */
    void evaluate(OrderEvent event);
}
