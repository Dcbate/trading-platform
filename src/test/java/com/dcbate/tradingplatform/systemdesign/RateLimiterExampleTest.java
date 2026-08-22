package com.dcbate.tradingplatform.systemdesign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * Rate limiting via the classic token-bucket algorithm. This app doesn't have an
 * API-protecting rate limiter today — only fraud-signal "velocity tracking"
 * ({@code trading.service.OrderVelocityTracker}, {@code payment.service.PaymentVelocityTracker}),
 * which is a genuinely different thing. See docs/TECH_STACK_INTERVIEW_GUIDE.md, "Rate limiting,"
 * for the honest distinction.
 *
 * <p>This is what the real thing would look like: a bucket that holds a fixed number of tokens,
 * refills steadily over time, and rejects a request the instant it's empty. A pluggable clock
 * ({@code Supplier<Instant>}) stands in for the real one so refill timing can be tested without
 * ever actually sleeping.
 */
class RateLimiterExampleTest {

    static class TokenBucket {
        private final int capacity;
        private final double tokensPerMillisecond;
        private final Supplier<Instant> clock;
        private double tokens;
        private Instant lastRefill;

        TokenBucket(int capacity, double refillTokensPerSecond, Supplier<Instant> clock) {
            this.capacity = capacity;
            this.tokensPerMillisecond = refillTokensPerSecond / 1000.0;
            this.clock = clock;
            this.tokens = capacity;
            this.lastRefill = clock.get();
        }

        synchronized boolean tryConsume() {
            refill();
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }

        private void refill() {
            Instant now = clock.get();
            long elapsedMs = Duration.between(lastRefill, now).toMillis();
            if (elapsedMs > 0) {
                tokens = Math.min(capacity, tokens + elapsedMs * tokensPerMillisecond);
                lastRefill = now;
            }
        }
    }

    @Test
    void allowsRequestsUpToTheBucketsCapacityThenRejects() {
        Instant fixedTime = Instant.parse("2026-01-01T00:00:00Z");
        TokenBucket bucket = new TokenBucket(3, 1, () -> fixedTime); // 3 tokens, no time passing

        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isTrue();
        // The bucket is now empty — a 4th request in the same instant is rejected, not queued.
        assertThat(bucket.tryConsume()).isFalse();
    }

    @Test
    void refillsOverTimeSoARejectedRequestCanSucceedLater() {
        Instant[] currentTime = {Instant.parse("2026-01-01T00:00:00Z")};
        TokenBucket bucket = new TokenBucket(1, 1, () -> currentTime[0]); // 1 token, refills 1/sec

        assertThat(bucket.tryConsume()).isTrue();
        assertThat(bucket.tryConsume()).isFalse(); // empty immediately after

        currentTime[0] = currentTime[0].plusSeconds(1); // simulate exactly one second passing
        assertThat(bucket.tryConsume()).isTrue(); // refilled exactly one token
    }
}
