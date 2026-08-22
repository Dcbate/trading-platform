package com.dcbate.tradingplatform.game.service;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    /** True for stocks, false for FX pairs — dividends (see {@code GameServiceImpl.settleDividends}) only apply to stocks, the same way a real dividend never comes from a currency pair. */
    boolean isStockSymbol(String symbol);

    /** Starts a tier-wide speed boost — see {@code GameMarketServiceImpl}'s javadoc for why this is per-tier, not per-session. */
    void activateSpeedBoost(GameDifficulty difficulty);

    /** 1 normally; higher while a boost triggered by any player in this tier is still active. */
    int currentSpeedMultiplier(GameDifficulty difficulty);

    /**
     * True if {@code symbol}'s current trend regime is presently biased up, false if down, empty
     * if the symbol is unknown. The same readable momentum a player could eyeball off the ticker —
     * see {@code GameServiceImpl.settleAdvisorTip}, which builds the wealth-manager tip on top of
     * this rather than anything hidden from the player.
     */
    Optional<Boolean> currentTrendUp(GameDifficulty difficulty, String symbol);

    /**
     * One headline-worthy market event — a crash or a chaos-mode spike, never a routine tick.
     * {@code priceUp}/{@code magnitudePercent} are machine-readable duplicates of what the
     * headline already says in prose — added so a client (the actionable event prompt) can
     * react to a specific direction/size without parsing free text.
     */
    record GameMarketEvent(String symbol, String headline, boolean priceUp, int magnitudePercent, Instant occurredAt) {}

    /** Most recent events for a difficulty's market, newest first — capped, not a full history. */
    List<GameMarketEvent> recentEvents(GameDifficulty difficulty);
}
