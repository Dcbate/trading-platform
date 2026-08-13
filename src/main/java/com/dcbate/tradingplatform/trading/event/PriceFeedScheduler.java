package com.dcbate.tradingplatform.trading.event;

import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.trading.service.PriceFeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class PriceFeedScheduler {

    private final PriceFeedService priceFeedService;
    private final TradingProperties tradingProperties;

    @Scheduled(fixedRateString = "${trading.price-feed.tick-interval-ms}")
    public void tick() {
        tradingProperties.symbols().forEach(this::publishSafely);
    }

    private void publishSafely(String symbol) {
        try {
            priceFeedService.publishTick(symbol);
        } catch (Exception e) {
            log.error("Failed to publish price tick for symbol={}: {}", symbol, e.getMessage());
        }
    }
}
