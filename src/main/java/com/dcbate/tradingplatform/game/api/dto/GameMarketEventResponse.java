package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.game.service.GameMarketService.GameMarketEvent;
import java.time.Instant;

public record GameMarketEventResponse(String symbol, String headline, boolean priceUp, int magnitudePercent, Instant occurredAt) {

    public static GameMarketEventResponse from(GameMarketEvent event) {
        return new GameMarketEventResponse(event.symbol(), event.headline(), event.priceUp(), event.magnitudePercent(), event.occurredAt());
    }
}
