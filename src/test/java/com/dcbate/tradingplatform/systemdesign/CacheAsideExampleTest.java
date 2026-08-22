package com.dcbate.tradingplatform.systemdesign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * The cache-aside pattern {@code trading.service.PriceFeedServiceImpl} uses with Redis for real
 * (check the cache first, only compute/fetch on a miss, then fill the cache for next time). See
 * docs/TECH_STACK_INTERVIEW_GUIDE.md, "Caching."
 *
 * <p>A plain {@link HashMap} stands in for Redis here so the mechanism is visible without a
 * running cache server.
 */
class CacheAsideExampleTest {

    static class CacheAsideLookup<K, V> {
        private final Map<K, V> cache = new HashMap<>();
        private final Function<K, V> realSource;

        CacheAsideLookup(Function<K, V> realSource) {
            this.realSource = realSource;
        }

        V get(K key) {
            if (cache.containsKey(key)) {
                return cache.get(key); // cache hit — the real source is never touched
            }
            V value = realSource.apply(key); // cache miss — go compute/fetch it
            cache.put(key, value);
            return value;
        }
    }

    @Test
    void onlyHitsTheRealSourceOnceForRepeatedLookupsOfTheSameKey() {
        AtomicInteger realSourceCalls = new AtomicInteger();
        CacheAsideLookup<String, Double> rateLookup = new CacheAsideLookup<>(symbol -> {
            realSourceCalls.incrementAndGet();
            return 1.27; // pretend this is a slow computed/fetched exchange rate
        });

        double first = rateLookup.get("GBP/USD");
        double second = rateLookup.get("GBP/USD");
        double third = rateLookup.get("GBP/USD");

        assertThat(first).isEqualTo(second).isEqualTo(third).isEqualTo(1.27);
        assertThat(realSourceCalls.get()).isEqualTo(1);
    }

    @Test
    void differentKeysEachTriggerTheirOwnCacheMissOnce() {
        AtomicInteger realSourceCalls = new AtomicInteger();
        CacheAsideLookup<String, Double> rateLookup = new CacheAsideLookup<>(symbol -> {
            realSourceCalls.incrementAndGet();
            return switch (symbol) {
                case "GBP/USD" -> 1.27;
                case "EUR/USD" -> 1.08;
                default -> throw new IllegalArgumentException(symbol);
            };
        });

        assertThat(rateLookup.get("GBP/USD")).isEqualTo(1.27);
        assertThat(rateLookup.get("EUR/USD")).isEqualTo(1.08);
        assertThat(rateLookup.get("GBP/USD")).isEqualTo(1.27); // cached from before

        assertThat(realSourceCalls.get()).isEqualTo(2);
    }
}
