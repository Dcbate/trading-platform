package com.dcbate.tradingplatform.game.service;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @see GameMarketService
 *
 * All players in the same difficulty tier see the same live prices — one shared random walk per
 * tier, not one per session. That's simpler than isolating a market per session and arguably more
 * realistic: everyone in "Trader" mode is trading the same simulated market, same as a real FX
 * desk's clients all see the same quote.
 */
@Slf4j
@Service
public class GameMarketServiceImpl implements GameMarketService {

    private static final Map<String, BigDecimal> FX_SEED_PRICES = new LinkedHashMap<>();
    private static final Map<String, BigDecimal> STOCK_SEED_PRICES = new LinkedHashMap<>();

    static {
        FX_SEED_PRICES.put("EUR/USD", new BigDecimal("1.0800"));
        FX_SEED_PRICES.put("GBP/USD", new BigDecimal("1.2700"));
        FX_SEED_PRICES.put("USD/JPY", new BigDecimal("149.50"));

        STOCK_SEED_PRICES.put("AAPL", new BigDecimal("190.00"));
        STOCK_SEED_PRICES.put("MSFT", new BigDecimal("420.00"));
        STOCK_SEED_PRICES.put("NVDA", new BigDecimal("875.00"));
        STOCK_SEED_PRICES.put("GOOGL", new BigDecimal("165.00"));
        STOCK_SEED_PRICES.put("AMZN", new BigDecimal("180.00"));
        STOCK_SEED_PRICES.put("TSLA", new BigDecimal("250.00"));
        STOCK_SEED_PRICES.put("META", new BigDecimal("505.00"));
        STOCK_SEED_PRICES.put("NFLX", new BigDecimal("650.00"));
        STOCK_SEED_PRICES.put("INTC", new BigDecimal("32.00"));
        STOCK_SEED_PRICES.put("AMD", new BigDecimal("165.00"));
    }

    private static final Set<String> FX_SYMBOLS = FX_SEED_PRICES.keySet();
    private static final double CHAOS_SPIKE_PROBABILITY = 0.04;
    private static final double CHAOS_SPIKE_MOVE_FRACTION = 0.20;

    private final Map<GameDifficulty, Map<String, BigDecimal>> pricesByDifficulty = new EnumMap<>(GameDifficulty.class);

    @PostConstruct
    void seed() {
        for (GameDifficulty difficulty : GameDifficulty.values()) {
            Map<String, BigDecimal> prices = new ConcurrentHashMap<>();
            prices.putAll(FX_SEED_PRICES);
            prices.putAll(STOCK_SEED_PRICES);
            pricesByDifficulty.put(difficulty, prices);
        }
    }

    @Override
    public void tick() {
        for (GameDifficulty difficulty : GameDifficulty.values()) {
            Map<String, BigDecimal> prices = pricesByDifficulty.get(difficulty);
            for (String symbol : FX_SYMBOLS) {
                prices.compute(symbol, (s, previous) -> randomWalk(previous, difficulty.getFxVolatility(), difficulty.isChaosMode()));
            }
            for (String symbol : STOCK_SEED_PRICES.keySet()) {
                prices.compute(symbol, (s, previous) -> randomWalk(previous, difficulty.getStockVolatility(), difficulty.isChaosMode()));
            }
        }
    }

    private BigDecimal randomWalk(BigDecimal previous, double baseVolatility, boolean chaosMode) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double maxMoveFraction = baseVolatility;
        if (chaosMode && random.nextDouble() < CHAOS_SPIKE_PROBABILITY) {
            maxMoveFraction = CHAOS_SPIKE_MOVE_FRACTION;
        }
        double pctMove = (random.nextDouble() - 0.5) * 2 * maxMoveFraction;
        BigDecimal next = previous.add(previous.multiply(BigDecimal.valueOf(pctMove))).setScale(4, RoundingMode.HALF_UP);
        return next.signum() > 0 ? next : previous;
    }

    @Override
    public Map<String, BigDecimal> currentPrices(GameDifficulty difficulty) {
        return Map.copyOf(pricesByDifficulty.get(difficulty));
    }

    @Override
    public Optional<BigDecimal> currentPrice(GameDifficulty difficulty, String symbol) {
        return Optional.ofNullable(pricesByDifficulty.get(difficulty).get(symbol));
    }

    @Override
    public boolean isTradableSymbol(String symbol) {
        return FX_SYMBOLS.contains(symbol) || STOCK_SEED_PRICES.containsKey(symbol);
    }
}
