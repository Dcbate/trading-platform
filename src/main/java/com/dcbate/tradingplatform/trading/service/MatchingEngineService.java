package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.kafka.event.OrderValidatedEvent;
import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import java.util.List;

/** The matching core, driven by {@code MatchingEngineConsumerRunner}'s dedicated poll loop rather than a Spring listener. */
public interface MatchingEngineService {

    /** Matches the incoming order against its currencyPair's book and publishes any resulting trades. */
    List<TradeEvent> match(OrderValidatedEvent event);

    /** Total resting orders across every currencyPair's book — used for the health indicator. */
    int orderBookDepth();
}
