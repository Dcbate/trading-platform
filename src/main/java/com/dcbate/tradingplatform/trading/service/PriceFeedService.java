package com.dcbate.tradingplatform.trading.service;

import java.math.BigDecimal;
import java.util.Optional;

/** Stands in for a real market data feed (none exists) — see {@code PriceFeedServiceImpl}. */
public interface PriceFeedService {

    /** Generates and publishes one synthetic price tick for the given currencyPair. */
    void publishTick(String currencyPair);

    /**
     * The last cached price for {@code currencyPair} (e.g. a currency pair like {@code EUR/USD}), or
     * empty if no tick has been published/cached recently. Used by {@code AccountServiceImpl}'s
     * FX conversion — a real rate stream, not the order book, is what prices a conversion.
     */
    Optional<BigDecimal> currentPrice(String currencyPair);
}
