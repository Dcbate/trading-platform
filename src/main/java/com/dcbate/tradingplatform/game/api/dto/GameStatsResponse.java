package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import java.math.BigDecimal;
import java.util.List;

/**
 * A client's own Game Mode history — personal stats only, no other players. A leaderboard listing
 * real other users' scores would need every player's data exposed to every other player, which
 * this app doesn't do anywhere else (see {@code CallerPrincipal.requireOwner}); a leaderboard of
 * invented names would just be fabricated data, which this app has avoided everywhere else too
 * (see docs/DESIGN_DECISIONS.md). "Your best runs" is the honest version of a leaderboard here.
 */
public record GameStatsResponse(
        String clientId,
        int totalGames,
        int wins,
        double winRatePercent,
        BigDecimal bestTradePnl,
        List<GameDifficultyStat> perDifficulty) {

    public record GameDifficultyStat(GameDifficulty difficulty, int gamesPlayed, int wins, BigDecimal bestNetWorth) {
    }
}
