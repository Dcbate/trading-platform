package com.dcbate.tradingplatform.game.event;

import com.dcbate.tradingplatform.game.service.GameMarketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Ticks Game Mode's simulated market every 2 seconds — matches the real desk's tick cadence, but a fixed literal here rather than a config property since this is a fixed game rule, not deployment config. */
@Slf4j
@Component
@RequiredArgsConstructor
public class GameMarketScheduler {

    private final GameMarketService gameMarketService;

    @Scheduled(fixedRate = 2000)
    public void tick() {
        try {
            gameMarketService.tick();
        } catch (Exception e) {
            log.error("Failed to tick Game Mode market: {}", e.getMessage());
        }
    }
}
