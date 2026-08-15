package com.dcbate.tradingplatform.game.api.dto;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.domain.GameStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A full valuation snapshot — cash, positions marked to the live game market, loans with interest owed, and net worth against the goal. */
public record GameSessionResponse(
        UUID sessionId,
        String clientId,
        GameDifficulty difficulty,
        GameStatus status,
        BigDecimal cash,
        BigDecimal netWorth,
        BigDecimal goalAmount,
        long timeRemainingSeconds,
        Instant startedAt,
        Instant endsAt,
        List<GamePositionResponse> positions,
        List<GameLoanResponse> loans) {
}
