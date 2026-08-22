package com.dcbate.tradingplatform.game.service;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
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

    // Stock-only, every difficulty tier (unlike the chaos spike above, which is Rogue-only) — a
    // rare, outsized one-tick shock on top of whatever regime is already in play, modeling a real
    // market crash (or, less often, a rally) rather than the routine up/down drift the
    // regime+jitter model produces. 0.05% per stock symbol per tick works out to roughly 1-2
    // expected shocks across all 10 stocks over a full 15-30 minute session (confirmed by playing
    // several sessions through) — rare enough to be a genuine event, not so rare it's never seen.
    // Weighted toward crashes (65/35) rather than an even split — "stocks should crash sometimes"
    // was the original ask this mechanism was built for; rallies are a real but secondary flavor
    // on top, not a coin flip that could just as easily always favor the player.
    private static final double STOCK_SHOCK_PROBABILITY = 0.0005;
    private static final double STOCK_SHOCK_DOWN_ODDS = 0.65;
    private static final double STOCK_SHOCK_MIN_MOVE_FRACTION = 0.12;
    private static final double STOCK_SHOCK_MAX_MOVE_FRACTION = 0.30;

    // Headline variety is cosmetic only — never affects price math — but a single fixed template
    // reads as an obvious log line rather than "news," which is the whole point of surfacing these.
    private static final String[] CRASH_HEADLINES = {
            "%s crashes %d%% — panic selling wipes out gains",
            "%s craters %d%% in a single tick — a brutal drop",
            "%s plunges %d%% as sellers rush for the exit",
    };
    private static final String[] RALLY_HEADLINES = {
            "%s rallies %d%% — buyers pile in all at once",
            "%s rockets %d%% in a single tick — a stunning surge",
            "%s soars %d%% as a wave of buying hits the tape",
    };
    private static final String CHAOS_SPIKE_UP_HEADLINE = "%s spikes %d%% out of nowhere — a sudden surge";
    private static final String CHAOS_SPIKE_DOWN_HEADLINE = "%s plunges %d%% in a flash — Rogue mode chaos strikes";
    private static final int MAX_EVENTS = 20;

    // A player-triggered burst that compresses this many ticks' worth of regime movement/shock
    // rolls into the same 5-second scheduler window a normal tick already runs on — a genuine
    // fast-forward (more ticks = more regime progression, more chances for a crash/rally to roll),
    // not a cosmetic polling trick. Shared tier-wide since the market itself is (see class
    // javadoc): everyone currently in that difficulty feels the same speed-up. 3x rather than
    // higher keeps one boost from skipping a whole regime (10-40 ticks) past in a single window,
    // which would read as teleporting rather than fast-forwarding.
    private static final int SPEED_BOOST_TICK_MULTIPLIER = 3;
    private static final int SPEED_BOOST_DURATION_SECONDS = 60;

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
    private final Map<GameDifficulty, Deque<GameMarketEvent>> eventsByDifficulty = new EnumMap<>(GameDifficulty.class);

    // Plain ConcurrentHashMap, not EnumMap — writes come from arbitrary HTTP request threads
    // (activateSpeedBoost), reads from the scheduler thread (tick(), via currentSpeedMultiplier).
    private final Map<GameDifficulty, Instant> speedBoostExpiresAt = new ConcurrentHashMap<>();

    @PostConstruct
    void seed() {
        for (GameDifficulty difficulty : GameDifficulty.values()) {
            Map<String, SymbolState> states = new ConcurrentHashMap<>();
            FX_SEED_PRICES.forEach((symbol, price) -> states.put(symbol, new SymbolState(price)));
            STOCK_SEED_PRICES.forEach((symbol, price) -> states.put(symbol, new SymbolState(price)));
            statesByDifficulty.put(difficulty, states);
            eventsByDifficulty.put(difficulty, new ArrayDeque<>(MAX_EVENTS));
        }
    }

    /** Only the scheduler thread ever calls {@code tick()} (see {@code GameMarketScheduler}), so writes here are
     * effectively single-threaded — the {@code synchronized} block is purely to stay safe against concurrent reads
     * from {@link #recentEvents}, not against concurrent writers. */
    private void recordEvent(GameDifficulty difficulty, String symbol, String headline, boolean priceUp, int magnitudePercent) {
        Deque<GameMarketEvent> events = eventsByDifficulty.get(difficulty);
        synchronized (events) {
            events.addFirst(new GameMarketEvent(symbol, headline, priceUp, magnitudePercent, Instant.now()));
            while (events.size() > MAX_EVENTS) {
                events.removeLast();
            }
        }
    }

    @Override
    public void tick() {
        for (GameDifficulty difficulty : GameDifficulty.values()) {
            Map<String, SymbolState> states = statesByDifficulty.get(difficulty);
            int ticksThisInvocation = currentSpeedMultiplier(difficulty);
            for (int i = 0; i < ticksThisInvocation; i++) {
                for (String symbol : FX_SYMBOLS) {
                    advance(states.get(symbol), symbol, difficulty, difficulty.getFxVolatility(), difficulty.isChaosMode(), false);
                }
                for (String symbol : STOCK_SEED_PRICES.keySet()) {
                    advance(states.get(symbol), symbol, difficulty, difficulty.getStockVolatility(), difficulty.isChaosMode(), true);
                }
            }
        }
    }

    /**
     * Advances one symbol by one tick: rolls a new trend regime if the current one has run out,
     * applies that regime's bias plus a small residual jitter, layers an outsized symmetric spike
     * on top for chaos-mode difficulties only, and — for stock symbols, at every difficulty tier —
     * occasionally layers a rare shock (crash or, less often, rally) on top of that.
     */
    private void advance(SymbolState state, String symbol, GameDifficulty difficulty, double baseVolatility, boolean chaosMode, boolean isStock) {
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
            int magnitudePercent = (int) Math.round(Math.abs(spike) * 100);
            String headline = (spike > 0 ? CHAOS_SPIKE_UP_HEADLINE : CHAOS_SPIKE_DOWN_HEADLINE).formatted(symbol, magnitudePercent);
            recordEvent(difficulty, symbol, headline, spike > 0, magnitudePercent);
        }

        if (isStock && random.nextDouble() < STOCK_SHOCK_PROBABILITY) {
            double magnitude = STOCK_SHOCK_MIN_MOVE_FRACTION
                    + random.nextDouble() * (STOCK_SHOCK_MAX_MOVE_FRACTION - STOCK_SHOCK_MIN_MOVE_FRACTION);
            boolean isCrash = random.nextDouble() < STOCK_SHOCK_DOWN_ODDS;
            pctMove += isCrash ? -magnitude : magnitude;
            int magnitudePercent = (int) Math.round(magnitude * 100);
            String[] headlines = isCrash ? CRASH_HEADLINES : RALLY_HEADLINES;
            String headline = headlines[random.nextInt(headlines.length)].formatted(symbol, magnitudePercent);
            recordEvent(difficulty, symbol, headline, !isCrash, magnitudePercent);
            log.info("Game Mode stock {}: symbol={}, difficulty={}, move={}%",
                    isCrash ? "crash" : "rally", symbol, difficulty, magnitudePercent);
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

    @Override
    public boolean isStockSymbol(String symbol) {
        return STOCK_SEED_PRICES.containsKey(symbol);
    }

    @Override
    public void activateSpeedBoost(GameDifficulty difficulty) {
        speedBoostExpiresAt.put(difficulty, Instant.now().plusSeconds(SPEED_BOOST_DURATION_SECONDS));
    }

    @Override
    public int currentSpeedMultiplier(GameDifficulty difficulty) {
        Instant expiresAt = speedBoostExpiresAt.get(difficulty);
        return expiresAt != null && expiresAt.isAfter(Instant.now()) ? SPEED_BOOST_TICK_MULTIPLIER : 1;
    }

    @Override
    public Optional<Boolean> currentTrendUp(GameDifficulty difficulty, String symbol) {
        return Optional.ofNullable(statesByDifficulty.get(difficulty).get(symbol)).map(s -> s.trendPerTick >= 0);
    }

    @Override
    public List<GameMarketEvent> recentEvents(GameDifficulty difficulty) {
        Deque<GameMarketEvent> events = eventsByDifficulty.get(difficulty);
        synchronized (events) {
            return new ArrayList<>(events);
        }
    }
}
