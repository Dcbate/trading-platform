package com.dcbate.tradingplatform.trading.event;

import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.trading.service.PriceFeedService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron-driven trigger for {@link PriceFeedService#publishTick}, one tick per configured currency
 * pair on every run. A single pair's publish failure is caught and logged per-pair so one bad
 * tick can't stop the rest of the pairs from ticking in the same cycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceFeedScheduler {

    private final PriceFeedService priceFeedService;
    private final TradingProperties tradingProperties;

    @Scheduled(fixedRateString = "${trading.price-feed.tick-interval-ms}")
    public void tick() {
        tradingProperties.currencyPairs().forEach(this::publishSafely);
    }

    private void publishSafely(String currencyPair) {
        try {
            priceFeedService.publishTick(currencyPair);
        } catch (Exception e) {
            log.error("Failed to publish price tick for currencyPair={}: {}", currencyPair, e.getMessage());
        }
    }
}
