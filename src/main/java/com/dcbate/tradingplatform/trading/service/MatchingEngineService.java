package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.kafka.event.OrderValidatedEvent;
import com.dcbate.tradingplatform.kafka.event.TradeEvent;
import java.util.List;

public interface MatchingEngineService {

    /** Matches the incoming order against its symbol's book and publishes any resulting trades. */
    List<TradeEvent> match(OrderValidatedEvent event);

    /** Total resting orders across every symbol's book — used for the health indicator. */
    int orderBookDepth();
}
