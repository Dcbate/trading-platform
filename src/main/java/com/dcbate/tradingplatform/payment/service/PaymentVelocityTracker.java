package com.dcbate.tradingplatform.payment.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/**
 * Tracks a per-client sliding window of recent payment timestamps in memory. Mirrors
 * {@code trading.service.OrderVelocityTracker} — kept as a separate small class rather than a
 * shared abstraction, since order-velocity and payment-velocity are different semantic limits
 * that shouldn't share state. Single-instance only, same caveat as its trading counterpart.
 */
@Component
public class PaymentVelocityTracker {

    private final Map<String, Deque<Instant>> recentPaymentsByClient = new ConcurrentHashMap<>();

    /** Records {@code now} for {@code clientId} and returns how many payments fall within the window. */
    public int recordAndCount(String clientId, Instant now, Duration window) {
        Deque<Instant> timestamps = recentPaymentsByClient.computeIfAbsent(clientId, id -> new ConcurrentLinkedDeque<>());
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
