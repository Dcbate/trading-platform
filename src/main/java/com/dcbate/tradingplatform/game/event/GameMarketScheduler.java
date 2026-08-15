package com.dcbate.tradingplatform.game.event;

import com.dcbate.tradingplatform.game.service.GameMarketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticks Game Mode's simulated market every 5 seconds — deliberately slower than the real desk's
 * 2-second cadence (see {@code PriceFeedScheduler}). At 2-3 seconds a player never gets a chance
 * to actually read a trend before it's already moved again; 5 seconds gives a regime (see
 * {@code GameMarketServiceImpl}, 10-40 ticks) 50 seconds to several minutes of real time to
 * develop and be spotted. A fixed literal here rather than a config property since this is a
 * fixed game rule, not deployment config.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameMarketScheduler {

    private final GameMarketService gameMarketService;

    @Scheduled(fixedRate = 5000)
    public void tick() {
        try {
            gameMarketService.tick();
        } catch (Exception e) {
            log.error("Failed to tick Game Mode market: {}", e.getMessage());
        }
    }
}
