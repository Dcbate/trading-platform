package com.dcbate.tradingplatform.actuator;

import com.dcbate.tradingplatform.trading.service.MatchingEngineService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** Exposes current order-book depth via {@code /actuator/health} for at-a-glance operability. */
@Component
@RequiredArgsConstructor
public class MatchingEngineHealthIndicator implements HealthIndicator {

    private final MatchingEngineService matchingEngineService;

    @Override
    public Health health() {
        return Health.up().withDetail("restingOrders", matchingEngineService.orderBookDepth()).build();
    }
}
