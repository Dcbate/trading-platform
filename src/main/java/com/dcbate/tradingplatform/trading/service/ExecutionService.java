package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.kafka.event.TradeEvent;

public interface ExecutionService {

    /** Persists the trade, updates both orders' status, and journals the fill. */
    void recordTrade(TradeEvent event);
}
