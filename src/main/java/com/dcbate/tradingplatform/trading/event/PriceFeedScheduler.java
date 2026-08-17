package com.dcbate.tradingplatform.trading.event;

import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.trading.service.PriceFeedService;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Cron-driven trigger for {@link PriceFeedService#publishTick}, one tick per configured
 * instrument (currency pair, stock symbol, or crypto pair — the feed treats all three
 * identically) on every run. A single instrument's publish failure is caught and logged
 * per-instrument so one bad tick can't stop the rest from ticking in the same cycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceFeedScheduler {

    private final PriceFeedService priceFeedService;
    private final TradingProperties tradingProperties;

    @Scheduled(fixedRateString = "${trading.price-feed.tick-interval-ms}")
    public void tick() {
        Stream.of(tradingProperties.currencyPairs(), tradingProperties.stockSymbols(), tradingProperties.cryptoSymbols())
                .flatMap(List::stream)
                .forEach(this::publishSafely);
    }

    private void publishSafely(String currencyPair) {
        try {
            priceFeedService.publishTick(currencyPair);
        } catch (Exception e) {
            log.error("Failed to publish price tick for currencyPair={}: {}", currencyPair, e.getMessage());
        }
    }
}
