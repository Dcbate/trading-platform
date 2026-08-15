package com.dcbate.tradingplatform.game.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import java.math.BigDecimal;
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
}
