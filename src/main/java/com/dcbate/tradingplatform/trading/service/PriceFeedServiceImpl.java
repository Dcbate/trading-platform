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
 * Stands in for a real market data feed (none exists): generates a bounded random walk per
 * instrument — FX currency pairs and, identically, stock symbols, since both are just a string
 * key here — caches the latest price in Redis (TTL 1 minute), publishes to Kafka, and flags moves
 * beyond the configured threshold via {@link AnomalyDetector}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceFeedServiceImpl implements PriceFeedService {

    private static final Map<String, BigDecimal> SEED_PRICES = Map.ofEntries(
            Map.entry("EUR/USD", new BigDecimal("1.0800")),
            Map.entry("GBP/USD", new BigDecimal("1.2700")),
            Map.entry("USD/JPY", new BigDecimal("149.50")),
            Map.entry("USD/CHF", new BigDecimal("0.8800")),
            Map.entry("AUD/USD", new BigDecimal("0.6600")),
            // Stock seeds — same synthetic random-walk model as the FX pairs above, not a real
            // quote. Round starting points for well-known large-cap tickers, nothing more.
            Map.entry("AAPL", new BigDecimal("190.00")),
            Map.entry("MSFT", new BigDecimal("420.00")),
            Map.entry("GOOGL", new BigDecimal("165.00")),
            Map.entry("AMZN", new BigDecimal("180.00")),
            Map.entry("NVDA", new BigDecimal("875.00")),
            Map.entry("TSLA", new BigDecimal("250.00")),
            Map.entry("META", new BigDecimal("505.00")),
            // Crypto seeds — same synthetic random-walk model as everything else above, quoted
            // against USD by convention (how every real crypto exchange quotes it) regardless of
            // the funding account's own currency.
            Map.entry("BTC/USD", new BigDecimal("65000.00")),
            Map.entry("ETH/USD", new BigDecimal("3500.00")),
            Map.entry("SOL/USD", new BigDecimal("150.00")),
            Map.entry("XRP/USD", new BigDecimal("0.60")));
    private static final BigDecimal DEFAULT_SEED_PRICE = new BigDecimal("1.0000");
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
    public void publishTick(String currencyPair) {
        BigDecimal previous = readCachedPrice(currencyPair).orElseGet(() -> SEED_PRICES.getOrDefault(currencyPair, DEFAULT_SEED_PRICE));
        BigDecimal next = randomWalk(previous);

        kafkaEventPublisher.publish(topics.prices(), currencyPair, new PriceUpdateEvent(currencyPair, next, Instant.now()));
        cachePrice(currencyPair, next);
        checkAnomaly(currencyPair, previous, next);
    }

    private BigDecimal randomWalk(BigDecimal previous) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Mostly small ticks, with an occasional larger move to simulate a real news-driven
        // spike — otherwise the anomaly threshold below could never actually be reached.
        double maxMoveFraction = random.nextDouble() < SPIKE_PROBABILITY ? SPIKE_TICK_MOVE_FRACTION : NORMAL_TICK_MOVE_FRACTION;
        double pctMove = (random.nextDouble() - 0.5) * 2 * maxMoveFraction;
        BigDecimal next = previous.add(previous.multiply(BigDecimal.valueOf(pctMove))).setScale(4, RoundingMode.HALF_UP);
        return next.signum() > 0 ? next : previous;
    }

    private void checkAnomaly(String currencyPair, BigDecimal previous, BigDecimal next) {
        if (!isAnomalousMove(previous, next)) {
            return;
        }
        BigDecimal pctChange = percentChange(previous, next);
        meterRegistry.counter("price.anomalies", "currencyPair", currencyPair).increment();
        String description = "%s moved %s%% from %s to %s in one tick".formatted(currencyPair, pctChange, previous, next);
        AnomalyResult result = anomalyDetector.explain(new AnomalyContext("price:" + currencyPair, description));
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
    public Optional<BigDecimal> currentPrice(String currencyPair) {
        return readCachedPrice(currencyPair);
    }

    private Optional<BigDecimal> readCachedPrice(String currencyPair) {
        String cached = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + currencyPair);
        return Optional.ofNullable(cached).map(BigDecimal::new);
    }

    private void cachePrice(String currencyPair, BigDecimal price) {
        redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + currencyPair, price.toPlainString(), CACHE_TTL);
    }
}
