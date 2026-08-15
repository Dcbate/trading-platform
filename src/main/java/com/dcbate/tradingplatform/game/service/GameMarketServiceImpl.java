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
 * All players in the same difficulty tier see the same live prices — one shared walk per tier,
 * not one per session. That's simpler than isolating a market per session and arguably more
 * realistic: everyone in "Trader" mode is trading the same simulated market, same as a real FX
 * desk's clients all see the same quote.
 *
 * <p>Prices move in <b>regimes</b>, not a pure symmetric random walk. A pure walk (equal
 * up/down odds every tick, no persistence) is what the first version of this class did, and it
 * has zero expected drift by construction — no amount of skill changes the odds, so a session's
 * net worth just wanders near its starting point for the whole game (confirmed by actually
 * playing it: Apprentice never got anywhere near its goal). A regime is a run of ticks that all
 * lean the same direction — the thing a player can actually *read* off the ticker (three greens
 * in a row) and trade against, the same way short-term momentum is a real, well-documented
 * feature of real markets over seconds-to-minutes timeframes. Each symbol still moves
 * independently and regime direction is randomized, so nothing here is rigged to always go up —
 * only readable and thus tradeable, which is what makes the game winnable through attention and
 * leverage (loans) rather than a coin flip.
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
    private static final int MIN_REGIME_TICKS = 10;
    private static final int MAX_REGIME_TICKS = 40;

    /** One symbol's live state: its price, and the trend regime currently driving it. */
    private static final class SymbolState {
        private volatile BigDecimal price;
        private volatile double trendPerTick;
        private volatile int regimeTicksRemaining;

        private SymbolState(BigDecimal price) {
            this.price = price;
        }
    }

    private final Map<GameDifficulty, Map<String, SymbolState>> statesByDifficulty = new EnumMap<>(GameDifficulty.class);

    @PostConstruct
    void seed() {
        for (GameDifficulty difficulty : GameDifficulty.values()) {
            Map<String, SymbolState> states = new ConcurrentHashMap<>();
            FX_SEED_PRICES.forEach((symbol, price) -> states.put(symbol, new SymbolState(price)));
            STOCK_SEED_PRICES.forEach((symbol, price) -> states.put(symbol, new SymbolState(price)));
            statesByDifficulty.put(difficulty, states);
        }
    }

    @Override
    public void tick() {
        for (GameDifficulty difficulty : GameDifficulty.values()) {
            Map<String, SymbolState> states = statesByDifficulty.get(difficulty);
            for (String symbol : FX_SYMBOLS) {
                advance(states.get(symbol), difficulty.getFxVolatility(), difficulty.isChaosMode());
            }
            for (String symbol : STOCK_SEED_PRICES.keySet()) {
                advance(states.get(symbol), difficulty.getStockVolatility(), difficulty.isChaosMode());
            }
        }
    }

    /**
     * Advances one symbol by one tick: rolls a new trend regime if the current one has run out,
     * applies that regime's bias plus a small residual jitter, and — for chaos-mode difficulties
     * only — occasionally layers an outsized one-tick spike on top.
     */
    private void advance(SymbolState state, double baseVolatility, boolean chaosMode) {
        ThreadLocalRandom random = ThreadLocalRandom.current();

        if (state.regimeTicksRemaining <= 0) {
            // Magnitude drawn from the upper half of the volatility band so every regime reads as
            // an actual trend, not a wishy-washy near-zero drift indistinguishable from noise.
            double magnitude = baseVolatility * (0.5 + random.nextDouble() * 0.5);
            state.trendPerTick = random.nextBoolean() ? magnitude : -magnitude;
            state.regimeTicksRemaining = MIN_REGIME_TICKS + random.nextInt(MAX_REGIME_TICKS - MIN_REGIME_TICKS + 1);
        }
        state.regimeTicksRemaining--;

        double jitter = (random.nextDouble() - 0.5) * 2 * (baseVolatility * 0.3);
        double pctMove = state.trendPerTick + jitter;

        if (chaosMode && random.nextDouble() < CHAOS_SPIKE_PROBABILITY) {
            double spike = CHAOS_SPIKE_MOVE_FRACTION * (random.nextBoolean() ? 1 : -1);
            pctMove += spike;
        }

        BigDecimal next = state.price.add(state.price.multiply(BigDecimal.valueOf(pctMove))).setScale(4, RoundingMode.HALF_UP);
        state.price = next.signum() > 0 ? next : state.price;
    }

    @Override
    public Map<String, BigDecimal> currentPrices(GameDifficulty difficulty) {
        Map<String, BigDecimal> snapshot = new LinkedHashMap<>();
        statesByDifficulty.get(difficulty).forEach((symbol, state) -> snapshot.put(symbol, state.price));
        return snapshot;
    }

    @Override
    public Optional<BigDecimal> currentPrice(GameDifficulty difficulty, String symbol) {
        return Optional.ofNullable(statesByDifficulty.get(difficulty).get(symbol)).map(s -> s.price);
    }

    @Override
    public boolean isTradableSymbol(String symbol) {
        return FX_SYMBOLS.contains(symbol) || STOCK_SEED_PRICES.containsKey(symbol);
    }
}
