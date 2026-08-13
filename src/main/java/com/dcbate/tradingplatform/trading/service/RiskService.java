package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.kafka.event.OrderEvent;

public interface RiskService {

    /** Checks the order against notional and velocity limits, then routes it accordingly. */
    void evaluate(OrderEvent event);
}
