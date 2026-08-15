package com.dcbate.tradingplatform.game.service;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRequest;
import com.dcbate.tradingplatform.game.api.dto.GameSessionResponse;
import com.dcbate.tradingplatform.game.api.dto.GameStatsResponse;
import com.dcbate.tradingplatform.game.api.dto.GameTradeRequest;
import com.dcbate.tradingplatform.game.api.dto.GameTradeResponse;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import java.util.List;
import java.util.UUID;

/**
 * Game Mode: start a session, take loans and place trades against the simulated game market, and
 * read back a live-valued snapshot. Win/lose isn't decided by a background job — every read or
 * action re-evaluates the session first (see {@code GameServiceImpl.evaluate}), so a client can't
 * see a stale IN_PROGRESS status after time's actually up.
 */
public interface GameService {

    /** Starts a new session for {@code clientId}, or returns their existing in-progress one if they already have one. */
    GameSessionResponse startSession(String clientId, GameDifficulty difficulty, CallerPrincipal caller);

    GameSessionResponse getSession(UUID sessionId, CallerPrincipal caller);

    GameSessionResponse takeLoan(UUID sessionId, GameLoanRequest request, CallerPrincipal caller);

    GameSessionResponse placeTrade(UUID sessionId, GameTradeRequest request, CallerPrincipal caller);

    List<GameTradeResponse> listTrades(UUID sessionId, CallerPrincipal caller);

    GameStatsResponse getStats(String clientId, CallerPrincipal caller);
}
