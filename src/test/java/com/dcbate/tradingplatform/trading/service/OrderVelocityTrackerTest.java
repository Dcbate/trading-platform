package com.dcbate.tradingplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderVelocityTrackerTest {

    private final OrderVelocityTracker tracker = new OrderVelocityTracker();

    @Test
    void countsAccumulateWithinTheWindow() {
        Instant now = Instant.now();

        tracker.recordAndCount("client-1", now, Duration.ofSeconds(60));
        tracker.recordAndCount("client-1", now.plusSeconds(1), Duration.ofSeconds(60));
        int count = tracker.recordAndCount("client-1", now.plusSeconds(2), Duration.ofSeconds(60));

        assertThat(count).isEqualTo(3);
    }

    @Test
    void entriesOutsideTheWindowAreDropped() {
        Instant now = Instant.now();

        tracker.recordAndCount("client-1", now, Duration.ofSeconds(10));
        int count = tracker.recordAndCount("client-1", now.plusSeconds(20), Duration.ofSeconds(10));

        assertThat(count).isEqualTo(1);
    }

    @Test
    void clientsAreTrackedIndependently() {
        Instant now = Instant.now();

        tracker.recordAndCount("client-1", now, Duration.ofSeconds(60));
        int countForOtherClient = tracker.recordAndCount("client-2", now, Duration.ofSeconds(60));

        assertThat(countForOtherClient).isEqualTo(1);
    }
}
