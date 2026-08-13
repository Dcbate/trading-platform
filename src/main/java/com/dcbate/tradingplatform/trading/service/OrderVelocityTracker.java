package com.dcbate.tradingplatform.trading.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/**
 * Tracks a per-client sliding window of recent order timestamps in memory. Single-instance only
 * — a multi-instance deployment would need this backed by Redis (sorted sets), which is a Phase
 * 3 concern once the service actually scales horizontally.
 */
@Component
public class OrderVelocityTracker {

    private final Map<String, Deque<Instant>> recentOrdersByClient = new ConcurrentHashMap<>();

    /** Records {@code now} for {@code clientId} and returns how many orders fall within the window. */
    public int recordAndCount(String clientId, Instant now, Duration window) {
        Deque<Instant> timestamps = recentOrdersByClient.computeIfAbsent(clientId, id -> new ConcurrentLinkedDeque<>());
        synchronized (timestamps) {
            timestamps.addLast(now);
            Instant cutoff = now.minus(window);
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(cutoff)) {
                timestamps.pollFirst();
            }
            return timestamps.size();
        }
    }
}
