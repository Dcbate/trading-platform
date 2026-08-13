package com.dcbate.tradingplatform.trading.service;

public interface PriceFeedService {

    /** Generates and publishes one synthetic price tick for the given symbol. */
    void publishTick(String symbol);
}
