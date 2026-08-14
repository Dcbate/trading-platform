package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.kafka.event.TradeEvent;

/** Sole writer of {@code FILLED}/{@code PARTIALLY_FILLED} order status and every {@code Trade} row. */
public interface ExecutionService {

    /** Persists the trade, updates both orders' status, and journals the fill. */
    void recordTrade(TradeEvent event);
}
