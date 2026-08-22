package com.dcbate.tradingplatform.game.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GameMarketServiceImplTest {

    private GameMarketServiceImpl marketService;

    @BeforeEach
    void setUp() {
        marketService = new GameMarketServiceImpl();
        marketService.seed();
    }

    @Test
    void pricesStartAtSeedValues() {
        Map<String, BigDecimal> prices = marketService.currentPrices(GameDifficulty.APPRENTICE);

        assertThat(prices.get("AAPL")).isEqualByComparingTo("190.00");
        assertThat(prices.get("EUR/USD")).isEqualByComparingTo("1.0800");
    }

    @Test
    void pricesStayPositiveAfterManyTicks() {
        for (int i = 0; i < 500; i++) {
            marketService.tick();
        }

        for (BigDecimal price : marketService.currentPrices(GameDifficulty.ROGUE).values()) {
            assertThat(price.signum()).isPositive();
        }
    }

    @Test
    void regimesProduceMeaningfulMovementNotJustNoise() {
        // A pure symmetric random walk with no persistence wanders only a few percent over this
        // many ticks; regime-driven trends should carry AAPL well outside a tight band at least
        // once across enough ticks to cover several regime rotations (each up to 40 ticks).
        BigDecimal start = marketService.currentPrice(GameDifficulty.APPRENTICE, "AAPL").orElseThrow();
        BigDecimal maxDeviation = BigDecimal.ZERO;

        for (int i = 0; i < 300; i++) {
            marketService.tick();
            BigDecimal price = marketService.currentPrice(GameDifficulty.APPRENTICE, "AAPL").orElseThrow();
            BigDecimal deviation = price.subtract(start).abs().divide(start, 4, java.math.RoundingMode.HALF_UP);
            if (deviation.compareTo(maxDeviation) > 0) {
                maxDeviation = deviation;
            }
        }

        assertThat(maxDeviation).isGreaterThan(new BigDecimal("0.05"));
    }

    @Test
    void differentDifficultiesMoveIndependently() {
        for (int i = 0; i < 50; i++) {
            marketService.tick();
        }

        BigDecimal apprenticePrice = marketService.currentPrice(GameDifficulty.APPRENTICE, "AAPL").orElseThrow();
        BigDecimal roguePrice = marketService.currentPrice(GameDifficulty.ROGUE, "AAPL").orElseThrow();

        // Both started at the same seed; independent regimes make an exact match across 50 ticks
        // of two much-differently-scaled walks practically impossible.
        assertThat(apprenticePrice).isNotEqualByComparingTo(roguePrice);
    }

    @Test
    void isTradableSymbolRecognizesFxAndStocks() {
        assertThat(marketService.isTradableSymbol("EUR/USD")).isTrue();
        assertThat(marketService.isTradableSymbol("AAPL")).isTrue();
        assertThat(marketService.isTradableSymbol("ZZZZ")).isFalse();
    }

    @Test
    void stocksOccasionallyCrashHard() {
        // At GameMarketServiceImpl.STOCK_SHOCK_PROBABILITY (0.05% per stock symbol per tick), 8000
        // ticks across 10 stock symbols gives an expected shock count comfortably above zero, and
        // shocks skew 65/35 toward crashes — this asserts a crash-sized drop actually fires within
        // a bounded run, not just that it compiles.
        BigDecimal previous = marketService.currentPrice(GameDifficulty.APPRENTICE, "AAPL").orElseThrow();
        BigDecimal worstSingleTickDrop = BigDecimal.ZERO;

        for (int i = 0; i < 8000; i++) {
            marketService.tick();
            BigDecimal price = marketService.currentPrice(GameDifficulty.APPRENTICE, "AAPL").orElseThrow();
            if (price.compareTo(previous) < 0) {
                BigDecimal drop = previous.subtract(price).divide(previous, 4, java.math.RoundingMode.HALF_UP);
                if (drop.compareTo(worstSingleTickDrop) > 0) {
                    worstSingleTickDrop = drop;
                }
            }
            previous = price;
        }

        // Regime+jitter alone (±2% stock volatility band at Apprentice) can't produce a single-tick
        // drop anywhere near this large — only a crash-flavored shock can.
        assertThat(worstSingleTickDrop).isGreaterThan(new BigDecimal("0.10"));
    }

    @Test
    void stocksOccasionallyRallyHard() {
        // Same mechanism as the crash test above, just reading the upside: shocks are 35% rallies,
        // so a large enough run should also produce at least one outsized single-tick gain.
        BigDecimal previous = marketService.currentPrice(GameDifficulty.APPRENTICE, "MSFT").orElseThrow();
        BigDecimal bestSingleTickGain = BigDecimal.ZERO;

        for (int i = 0; i < 20000; i++) {
            marketService.tick();
            BigDecimal price = marketService.currentPrice(GameDifficulty.APPRENTICE, "MSFT").orElseThrow();
            if (price.compareTo(previous) > 0) {
                BigDecimal gain = price.subtract(previous).divide(previous, 4, java.math.RoundingMode.HALF_UP);
                if (gain.compareTo(bestSingleTickGain) > 0) {
                    bestSingleTickGain = gain;
                }
            }
            previous = price;
        }

        assertThat(bestSingleTickGain).isGreaterThan(new BigDecimal("0.10"));
    }

    @Test
    void crashesAreRecordedAsHeadlineEvents() {
        for (int i = 0; i < 8000 && marketService.recentEvents(GameDifficulty.APPRENTICE).isEmpty(); i++) {
            marketService.tick();
        }

        List<GameMarketService.GameMarketEvent> events = marketService.recentEvents(GameDifficulty.APPRENTICE);
        assertThat(events).isNotEmpty();
        assertThat(events.getFirst().headline()).containsPattern("\\d+%");
        assertThat(events.getFirst().symbol()).isNotBlank();
        // priceUp/magnitudePercent are the machine-readable form of what the headline already says
        // in prose — the actionable event prompt reads these instead of parsing free text.
        assertThat(events.getFirst().magnitudePercent()).isGreaterThan(0);
        assertThat(events.getFirst().headline()).contains(events.getFirst().magnitudePercent() + "%");
        // Newest first: the most recently ticked event should never be older than an earlier one.
        for (int i = 1; i < events.size(); i++) {
            assertThat(events.get(i - 1).occurredAt()).isAfterOrEqualTo(events.get(i).occurredAt());
        }
    }

    @Test
    void speedBoostMultipliesCurrentSpeedForItsTierOnly() {
        assertThat(marketService.currentSpeedMultiplier(GameDifficulty.ROGUE)).isEqualTo(1);

        marketService.activateSpeedBoost(GameDifficulty.ROGUE);

        assertThat(marketService.currentSpeedMultiplier(GameDifficulty.ROGUE)).isEqualTo(3);
        // Boosting one tier doesn't touch another — the market is shared per-tier, so a boost is
        // scoped the same way (see GameMarketServiceImpl's class javadoc).
        assertThat(marketService.currentSpeedMultiplier(GameDifficulty.APPRENTICE)).isEqualTo(1);
    }

    @Test
    void boostedTickGeneratesMoreEventsThanUnboostedOverTheSameNumberOfInvocations() {
        // TRADER has no chaos mode, so the only event source is the stock shock (0.05% per stock
        // symbol per tick — 10 stocks). Unboosted, 800 external tick() calls apply 800 internal
        // ticks (~4 expected events); boosted, each external call applies 3 internal ticks (~12
        // expected events). Two well-separated Poisson means (4 vs 12) keep this from flipping by
        // chance, the same statistical-confidence approach the crash/rally tests above already use.
        GameMarketServiceImpl unboosted = new GameMarketServiceImpl();
        unboosted.seed();
        GameMarketServiceImpl boosted = new GameMarketServiceImpl();
        boosted.seed();
        boosted.activateSpeedBoost(GameDifficulty.TRADER);

        for (int i = 0; i < 800; i++) {
            unboosted.tick();
            boosted.tick();
        }

        int unboostedEvents = unboosted.recentEvents(GameDifficulty.TRADER).size();
        int boostedEvents = boosted.recentEvents(GameDifficulty.TRADER).size();
        assertThat(boostedEvents).isGreaterThan(unboostedEvents);
    }

    @Test
    void eventLogIsCappedAtTwentyEntries() {
        for (int i = 0; i < 30000; i++) {
            marketService.tick();
        }

        assertThat(marketService.recentEvents(GameDifficulty.ROGUE).size()).isLessThanOrEqualTo(20);
    }
}
