package com.dcbate.tradingplatform.game.service;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/**
 * Game Mode's own simulated market — deliberately a separate instance from
 * {@code trading.service.PriceFeedService}, the real FX/stock desk's feed. Mixing the two would
 * mean a game session's price swings could either leak into real quoted prices or be constrained
 * by them; keeping them apart means Game Mode can run wide, difficulty-scaled volatility (up to
 * Rogue mode's flash crashes) with zero risk of ever touching a real price a real order could fill
 * against.
 */
public interface GameMarketService {

    /** Advances every difficulty's market by one tick — see {@code GameMarketScheduler}. */
    void tick();

    /** Every tradable symbol's current price for the given difficulty's market. */
    Map<String, BigDecimal> currentPrices(GameDifficulty difficulty);

    Optional<BigDecimal> currentPrice(GameDifficulty difficulty, String symbol);

    boolean isTradableSymbol(String symbol);
}
