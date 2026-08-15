package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import java.math.BigDecimal;

/** The difficulty picker's catalog entry — parameters the player sees before starting a session. */
public record GameDifficultyResponse(
        GameDifficulty code,
        String displayName,
        BigDecimal goalAmount,
        BigDecimal startingCash,
        int durationMinutes,
        BigDecimal loanRateAnnualPercent,
        BigDecimal feePercent,
        double fxVolatilityPercent,
        double stockVolatilityPercent,
        boolean chaosMode) {

    public static GameDifficultyResponse from(GameDifficulty d) {
        return new GameDifficultyResponse(
                d, d.getDisplayName(), d.getGoalAmount(), d.getStartingCash(), d.getDurationMinutes(),
                d.getLoanRateAnnualPercent(), d.getFeeRate().multiply(BigDecimal.valueOf(100)),
                d.getFxVolatility() * 100, d.getStockVolatility() * 100, d.isChaosMode());
    }
}
