package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.domain.GameStatus;
import com.dcbate.tradingplatform.domain.OrderSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A full valuation snapshot — cash, positions marked to the live game market, loans with interest
 * owed, savings with interest earned, and net worth against the goal. {@code savingsBalance} is
 * the live value (already-settled balance plus interest accrued since the last settle, computed
 * fresh on every read) — the same "ticks up live without a DB write per poll" treatment as a
 * loan's interest owed. {@code currentStreak} is the live count of consecutive profitable closed
 * trades, most-recent-first — a pure read of already-recorded {@code GameTrade} rows, not a new
 * persisted counter (see {@code GameServiceImpl.computeCurrentStreak}).
 */
public record GameSessionResponse(
        UUID sessionId,
        String clientId,
        GameDifficulty difficulty,
        GameStatus status,
        BigDecimal cash,
        BigDecimal savingsBalance,
        BigDecimal netWorth,
        BigDecimal goalAmount,
        long timeRemainingSeconds,
        int currentStreak,
        Instant startedAt,
        Instant endsAt,
        Instant speedBoostAvailableAt,
        int marketSpeedMultiplier,
        boolean advisorHired,
        String advisorTipSymbol,
        OrderSide advisorTipSide,
        Instant advisorNextTipAt,
        BigDecimal totalDividendsPaid,
        BigDecimal totalTaxPaid,
        List<GamePositionResponse> positions,
        List<GameLoanResponse> loans) {
}
