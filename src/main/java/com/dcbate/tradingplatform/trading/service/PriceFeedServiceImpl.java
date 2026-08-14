package com.dcbate.tradingplatform.trading.service;

import com.dcbate.tradingplatform.ai.AnomalyContext;
import com.dcbate.tradingplatform.ai.AnomalyDetector;
import com.dcbate.tradingplatform.ai.AnomalyResult;
import com.dcbate.tradingplatform.config.KafkaTopicsProperties;
import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.kafka.KafkaEventPublisher;
import com.dcbate.tradingplatform.kafka.event.PriceUpdateEvent;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Stands in for a real market data feed (none exists for Phase 1): generates a bounded random
 * walk per symbol, caches the latest price in Redis (TTL 1 minute), publishes to Kafka, and
 * flags moves beyond the configured threshold via {@link AnomalyDetector}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceFeedServiceImpl implements PriceFeedService {

    private static final Map<String, BigDecimal> SEED_PRICES = Map.of(
            "AAPL", new BigDecimal("150.00"),
            "GOOG", new BigDecimal("140.00"),
            "TSLA", new BigDecimal("250.00"),
            "MSFT", new BigDecimal("420.00"),
            "AMZN", new BigDecimal("180.00"));
    private static final BigDecimal DEFAULT_SEED_PRICE = new BigDecimal("100.00");
    private static final Duration CACHE_TTL = Duration.ofMinutes(1);
    private static final String CACHE_KEY_PREFIX = "price:";
    private static final double NORMAL_TICK_MOVE_FRACTION = 0.02;
    private static final double SPIKE_TICK_MOVE_FRACTION = 0.15;
    private static final double SPIKE_PROBABILITY = 0.05;

    private final StringRedisTemplate redisTemplate;
    private final KafkaEventPublisher kafkaEventPublisher;
    private final KafkaTopicsProperties topics;
    private final TradingProperties tradingProperties;
    private final AnomalyDetector anomalyDetector;
    private final MeterRegistry meterRegistry;

    @Override
    public void publishTick(String symbol) {
        BigDecimal previous = readCachedPrice(symbol).orElseGet(() -> SEED_PRICES.getOrDefault(symbol, DEFAULT_SEED_PRICE));
        BigDecimal next = randomWalk(previous);

        kafkaEventPublisher.publish(topics.prices(), symbol, new PriceUpdateEvent(symbol, next, Instant.now()));
        cachePrice(symbol, next);
        checkAnomaly(symbol, previous, next);
    }

    private BigDecimal randomWalk(BigDecimal previous) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Mostly small ticks, with an occasional larger move to simulate a real news-driven
        // spike — otherwise the anomaly threshold below could never actually be reached.
        double maxMoveFraction = random.nextDouble() < SPIKE_PROBABILITY ? SPIKE_TICK_MOVE_FRACTION : NORMAL_TICK_MOVE_FRACTION;
        double pctMove = (random.nextDouble() - 0.5) * 2 * maxMoveFraction;
        BigDecimal next = previous.add(previous.multiply(BigDecimal.valueOf(pctMove))).setScale(2, RoundingMode.HALF_UP);
        return next.signum() > 0 ? next : previous;
    }

    private void checkAnomaly(String symbol, BigDecimal previous, BigDecimal next) {
        if (!isAnomalousMove(previous, next)) {
            return;
        }
        BigDecimal pctChange = percentChange(previous, next);
        meterRegistry.counter("price.anomalies", "symbol", symbol).increment();
        String description = "%s moved %s%% from %s to %s in one tick".formatted(symbol, pctChange, previous, next);
        AnomalyResult result = anomalyDetector.explain(new AnomalyContext("price:" + symbol, description));
        log.warn("Price anomaly detected: {} (aiEnriched={})", result.explanation(), result.aiEnriched());
    }

    boolean isAnomalousMove(BigDecimal previous, BigDecimal next) {
        if (previous.signum() == 0) {
            return false;
        }
        return percentChange(previous, next).compareTo(tradingProperties.priceFeed().anomalyThresholdPercent()) > 0;
    }

    private BigDecimal percentChange(BigDecimal previous, BigDecimal next) {
        return next.subtract(previous).abs().divide(previous, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
    }

    @Override
    public Optional<BigDecimal> currentPrice(String symbol) {
        return readCachedPrice(symbol);
    }

    private Optional<BigDecimal> readCachedPrice(String symbol) {
        String cached = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + symbol);
        return Optional.ofNullable(cached).map(BigDecimal::new);
    }

    private void cachePrice(String symbol, BigDecimal price) {
        redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + symbol, price.toPlainString(), CACHE_TTL);
    }
}
