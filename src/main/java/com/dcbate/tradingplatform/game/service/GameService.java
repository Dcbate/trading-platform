package com.dcbate.tradingplatform.game.service;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.game.api.dto.GameDebriefResponse;
import com.dcbate.tradingplatform.game.api.dto.GameInsuranceRequest;
import com.dcbate.tradingplatform.game.api.dto.GameLeaderboardResponse;
import com.dcbate.tradingplatform.game.api.dto.GameLeaderboardSortBy;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRepayRequest;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRequest;
import com.dcbate.tradingplatform.game.api.dto.GameSavingsRequest;
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

    /** Starts a new session for {@code clientId}, or returns their existing in-progress one if they already have one — {@code GameSessionStartResult.created} tells the caller which happened. */
    GameSessionStartResult startSession(String clientId, GameDifficulty difficulty, CallerPrincipal caller);

    GameSessionResponse getSession(UUID sessionId, CallerPrincipal caller);

    GameSessionResponse takeLoan(UUID sessionId, GameLoanRequest request, CallerPrincipal caller);

    /** Speeds up the shared market for this session's difficulty tier for 60s; throws {@code GameSpeedBoostOnCooldownException} if still on cooldown. */
    GameSessionResponse activateSpeedBoost(UUID sessionId, CallerPrincipal caller);

    /** Pays a one-time fee for a wealth manager who periodically tips a symbol, right ~62% of the time; throws {@code GameAdvisorAlreadyHiredException} if already hired. */
    GameSessionResponse hireAdvisor(UUID sessionId, CallerPrincipal caller);

    /** Pays a one-time premium to floor a held position's downside at 85% of its average cost; throws {@code GamePositionAlreadyInsuredException} if already insured. */
    GameSessionResponse purchaseInsurance(UUID sessionId, GameInsuranceRequest request, CallerPrincipal caller);

    /** Applies a repayment to accrued interest first, then outstanding principal — same order the real loan repayment uses. */
    GameSessionResponse repayLoan(UUID sessionId, UUID gameLoanId, GameLoanRepayRequest request, CallerPrincipal caller);

    GameSessionResponse placeTrade(UUID sessionId, GameTradeRequest request, CallerPrincipal caller);

    /** Moves cash into savings, folding in interest accrued since the last touch first — see {@code GameServiceImpl.pendingSavingsInterest}. */
    GameSessionResponse depositToSavings(UUID sessionId, GameSavingsRequest request, CallerPrincipal caller);

    /** Moves savings back to cash; throws {@code GameInsufficientSavingsException} if the balance (including live-accrued interest) can't cover it. */
    GameSessionResponse withdrawFromSavings(UUID sessionId, GameSavingsRequest request, CallerPrincipal caller);

    List<GameTradeResponse> listTrades(UUID sessionId, CallerPrincipal caller);

    GameStatsResponse getStats(String clientId, CallerPrincipal caller);

    /**
     * Top 10 sessions for a difficulty, ranked per {@code sortBy} — {@code NET_WORTH} across every
     * finished session, {@code FASTEST_WIN} across WON sessions only. {@code viewerClientId} is
     * optional (a guest with no identity can still browse it) and only ever used to flag which row,
     * if any, is the caller's own — no client identifier is ever returned in the response itself.
     */
    GameLeaderboardResponse getLeaderboard(GameDifficulty difficulty, GameLeaderboardSortBy sortBy, String viewerClientId);

    /** Only available once a session has ended — throws {@code GameSessionStillInProgressException} otherwise. */
    GameDebriefResponse getDebrief(UUID sessionId, CallerPrincipal caller);
}
