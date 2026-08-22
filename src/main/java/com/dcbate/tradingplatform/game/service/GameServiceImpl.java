package com.dcbate.tradingplatform.game.service;

import com.dcbate.tradingplatform.ai.GameCoach;
import com.dcbate.tradingplatform.ai.GameDebriefContext;
import com.dcbate.tradingplatform.ai.GameDebriefResult;
import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.domain.GameLoan;
import com.dcbate.tradingplatform.domain.GamePosition;
import com.dcbate.tradingplatform.domain.GameSession;
import com.dcbate.tradingplatform.domain.GameStatus;
import com.dcbate.tradingplatform.domain.GameTrade;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.exception.GameAdvisorAlreadyHiredException;
import com.dcbate.tradingplatform.exception.GameInsufficientFundsException;
import com.dcbate.tradingplatform.exception.GameInsufficientPositionException;
import com.dcbate.tradingplatform.exception.GameInsufficientSavingsException;
import com.dcbate.tradingplatform.exception.GameLoanDeclinedException;
import com.dcbate.tradingplatform.exception.GameLoanNotFoundException;
import com.dcbate.tradingplatform.exception.GamePositionAlreadyInsuredException;
import com.dcbate.tradingplatform.exception.GameSessionNotActiveException;
import com.dcbate.tradingplatform.exception.GameSessionNotFoundException;
import com.dcbate.tradingplatform.exception.GameSessionStillInProgressException;
import com.dcbate.tradingplatform.exception.GameSpeedBoostOnCooldownException;
import com.dcbate.tradingplatform.game.api.dto.GameAchievementResponse;
import com.dcbate.tradingplatform.game.api.dto.GameDebriefResponse;
import com.dcbate.tradingplatform.game.api.dto.GameLeaderboardEntry;
import com.dcbate.tradingplatform.game.api.dto.GameLeaderboardResponse;
import com.dcbate.tradingplatform.game.api.dto.GameInsuranceRequest;
import com.dcbate.tradingplatform.game.api.dto.GameLeaderboardSortBy;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRepayRequest;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRequest;
import com.dcbate.tradingplatform.game.api.dto.GameLoanResponse;
import com.dcbate.tradingplatform.game.api.dto.GamePositionResponse;
import com.dcbate.tradingplatform.game.api.dto.GameSavingsRequest;
import com.dcbate.tradingplatform.game.api.dto.GameSessionResponse;
import com.dcbate.tradingplatform.game.api.dto.GameStatsResponse;
import com.dcbate.tradingplatform.game.api.dto.GameStatsResponse.GameDifficultyStat;
import com.dcbate.tradingplatform.game.api.dto.GameSymbolPerformanceResponse;
import com.dcbate.tradingplatform.game.api.dto.GameTradeRequest;
import com.dcbate.tradingplatform.game.api.dto.GameTradeResponse;
import com.dcbate.tradingplatform.game.repository.GameLoanRepository;
import com.dcbate.tradingplatform.game.repository.GamePositionRepository;
import com.dcbate.tradingplatform.game.repository.GameSessionRepository;
import com.dcbate.tradingplatform.game.repository.GameTradeRepository;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * @see GameService
 *
 * Every entry point calls {@link #evaluate} before doing anything else: a session that's actually
 * won, run out of time, or gone bankrupt since the last read gets its status flipped there, before
 * a loan/trade against it is even considered — a stale "still IN_PROGRESS" client can't sneak an
 * action in past the real deadline, since the server (not the client's timer) is what decides.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GameServiceImpl implements GameService {

    private static final int DAYS_PER_YEAR = 365;

    // Flat across every difficulty — deliberately not scaled up like the loan rate is, since
    // savings is meant to be the "boring but safe" option everywhere, not a min-maxed play that
    // gets better the harder the difficulty. Clearly worse than a good trade (a Home Run-sized
    // trade dwarfs what 3% APR could ever earn in a 15-30 minute session) but better than letting
    // idle cash earn nothing while waiting for a regime to turn.
    private static final BigDecimal SAVINGS_RATE_ANNUAL_PERCENT = new BigDecimal("3.00");

    // Same "compressed days per elapsed minute" treatment as LOAN_INTEREST_DAYS_PER_MINUTE, applied
    // to savings for the same reason: at 1 (real wall-clock days), £1,000 parked for a full 15-minute
    // Apprentice session earned about £1.23 — indistinguishable from zero, making the whole feature
    // pointless. The rate itself (3% vs. loans' 5-20%) is what keeps savings "boring but safe" — this
    // compression just makes that small-but-real number actually visible in a short session, the same
    // way it did for loans.
    private static final double SAVINGS_INTEREST_DAYS_PER_MINUTE = 6.0;

    // A trade on a symbol that just crashed/rallied/spiked, placed within this window of the event,
    // waives the fee entirely — a real, mechanical reward for reacting fast to the news ticker
    // (§3/§6a of docs/GAME_MODE.md) rather than a cosmetic-only badge. Reuses the same event log
    // the news ticker already reads (`GameMarketService.recentEvents`), so there's no new state to
    // track just for this.
    private static final int REACTION_WINDOW_SECONDS = 10;

    // A run of consecutive profitable closed trades (SELLs with realizedPnl > 0) needs to reach
    // this length to count as "on fire" for the achievement — short enough to be reachable in a
    // 15-30 minute session, long enough that it isn't handed out for two lucky trades in a row.
    private static final int HOT_STREAK_ACHIEVEMENT_THRESHOLD = 5;

    // How many "compressed days" (see pendingInterest's javadoc) each real elapsed minute counts
    // as. Raised from 1 after players reported loan interest was too small and too slow to be a
    // felt cost — with unlimited-looking borrowing capacity and negligible interest, taking the
    // biggest loan available and dumping it into trades had no real downside. 6 puts a held loan's
    // interest back in "meaningfully drags on your net worth" territory without re-creating the
    // opposite bug (an earlier version accrued a 20%/£5,000 loan up to £30,000 in 30 minutes).
    private static final double LOAN_INTEREST_DAYS_PER_MINUTE = 6.0;

    // A loan is a real credit decision, not a blank cheque: total outstanding principal (existing
    // loans plus the one being requested) can't exceed this multiple of the session's current net
    // worth. Mirrors a real lender sizing a credit line off net worth rather than handing out an
    // unlimited amount — and closes off the "take an arbitrarily large loan, it barely costs
    // anything" exploit that made the game too easy. 1.5x leaves room to genuinely lever up while
    // still requiring the player to have grown the account before borrowing heavily against it.
    private static final BigDecimal MAX_LOAN_TO_NET_WORTH_MULTIPLIER = new BigDecimal("1.5");

    // The speed boost itself is free (see GameMarketServiceImpl.SPEED_BOOST_DURATION_SECONDS) —
    // its only cost is this cooldown, long enough that one player mashing it can't keep the whole
    // shared tier permanently sped up for everyone else currently playing that difficulty.
    private static final int SPEED_BOOST_COOLDOWN_SECONDS = 90;

    // A one-time hire fee scaled off starting cash rather than a flat number, so hiring is a
    // proportionate real bet at every tier — negligible at Rogue and crushing at Apprentice would
    // both be wrong with a flat fee.
    private static final BigDecimal ADVISOR_HIRE_FEE_PERCENT_OF_STARTING_CASH = new BigDecimal("2.00");
    private static final int ADVISOR_TIP_INTERVAL_SECONDS = 90;

    // Fixed near the middle of the "genuine edge, not a cheat code" 60-65% band this was scoped
    // to — high enough to be worth paying for, low enough that blindly following every tip loses
    // money often enough to matter. Built on the same trend-regime state a player could read
    // themselves off the ticker (see GameMarketService.currentTrendUp) — the tip is automated,
    // deliberately-sometimes-wrong momentum reading, not a hidden signal.
    private static final double ADVISOR_TIP_ACCURACY = 0.62;

    // A modest bonus stacked on top of a growth asset that's also moving on its own, not the main
    // return driver — smaller than savings' 3% since it's rewarding patience on something already
    // capable of a much bigger move, not compensating for cash sitting idle. Stocks only, same as
    // real dividends never coming from a currency pair.
    private static final BigDecimal DIVIDEND_YIELD_ANNUAL_PERCENT = new BigDecimal("2.00");
    private static final double DIVIDEND_DAYS_PER_MINUTE = 6.0;

    // A one-time, non-refundable premium sized off what's actually being insured — insuring a
    // bigger position costs more, the same logic as a real premium scaling with sum insured.
    private static final BigDecimal INSURANCE_PREMIUM_PERCENT_OF_POSITION_VALUE = new BigDecimal("3.00");
    // The floor protects against a catastrophic drop beyond a deductible, not first-dollar loss —
    // 85% of avgCost means the player still eats the first 15% down move themselves, the same way
    // a real policy's excess works.
    private static final BigDecimal INSURANCE_FLOOR_PERCENT_OF_AVG_COST = new BigDecimal("0.85");

    // Flat across every difficulty, unlike the loan/fee rates — this is a levy on banked profit,
    // not a risk-tier-scaled cost of participating, so it doesn't need per-difficulty tuning the
    // way a credit- or volatility-linked cost would.
    private static final BigDecimal TAX_RATE_PERCENT = new BigDecimal("15.00");
    private static final int TAX_SETTLEMENT_INTERVAL_SECONDS = 60;

    private final GameSessionRepository sessionRepository;
    private final GamePositionRepository positionRepository;
    private final GameLoanRepository loanRepository;
    private final GameTradeRepository tradeRepository;
    private final GameMarketService marketService;
    private final GameCoach gameCoach;

    @Override
    @Transactional
    public GameSessionStartResult startSession(String clientId, GameDifficulty difficulty, CallerPrincipal caller) {
        caller.requireOwner(clientId);

        var existing = sessionRepository.findFirstByClientIdAndStatusOrderByStartedAtDesc(clientId, GameStatus.IN_PROGRESS);
        if (existing.isPresent()) {
            GameSession session = existing.get();
            evaluate(session);
            if (session.getStatus() == GameStatus.IN_PROGRESS) {
                return new GameSessionStartResult(toResponse(session), false);
            }
        }

        Instant now = Instant.now();
        GameSession session = GameSession.builder()
                .sessionId(UUID.randomUUID())
                .clientId(clientId)
                .difficulty(difficulty)
                .cash(difficulty.getStartingCash())
                .status(GameStatus.IN_PROGRESS)
                .startedAt(now)
                .endsAt(now.plus(Duration.ofMinutes(difficulty.getDurationMinutes())))
                .savingsBalance(BigDecimal.ZERO)
                .savingsLastAccrualAt(now)
                .speedBoostAvailableAt(now)
                .totalDividendsPaid(BigDecimal.ZERO)
                .totalRealizedPnlTaxed(BigDecimal.ZERO)
                .totalTaxPaid(BigDecimal.ZERO)
                .taxLastSettledAt(now)
                .build();
        sessionRepository.save(session);
        log.info("Game session started: sessionId={}, clientId={}, difficulty={}", session.getSessionId(), clientId, difficulty);
        return new GameSessionStartResult(toResponse(session), true);
    }

    @Override
    @Transactional
    public GameSessionResponse getSession(UUID sessionId, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        return toResponse(session);
    }

    @Override
    @Transactional
    public GameSessionResponse activateSpeedBoost(UUID sessionId, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        requireActive(session);

        Instant now = Instant.now();
        if (session.getSpeedBoostAvailableAt() != null && session.getSpeedBoostAvailableAt().isAfter(now)) {
            throw new GameSpeedBoostOnCooldownException(sessionId, session.getSpeedBoostAvailableAt());
        }

        marketService.activateSpeedBoost(session.getDifficulty());
        session.setSpeedBoostAvailableAt(now.plusSeconds(SPEED_BOOST_COOLDOWN_SECONDS));
        sessionRepository.save(session);

        log.info("Game speed boost activated: sessionId={}, difficulty={}", sessionId, session.getDifficulty());
        return toResponse(session);
    }

    @Override
    @Transactional
    public GameSessionResponse hireAdvisor(UUID sessionId, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        requireActive(session);

        if (session.isAdvisorHired()) {
            throw new GameAdvisorAlreadyHiredException(sessionId);
        }

        BigDecimal fee = session.getDifficulty().getStartingCash()
                .multiply(ADVISOR_HIRE_FEE_PERCENT_OF_STARTING_CASH)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (session.getCash().compareTo(fee) < 0) {
            throw new GameInsufficientFundsException(sessionId, fee, session.getCash());
        }

        session.setCash(session.getCash().subtract(fee));
        session.setAdvisorHired(true);
        session.setAdvisorHiredAt(Instant.now());
        sessionRepository.save(session);

        log.info("Game advisor hired: sessionId={}, fee={}", sessionId, fee);
        return toResponse(session);
    }

    @Override
    @Transactional
    public GameSessionResponse purchaseInsurance(UUID sessionId, GameInsuranceRequest request, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        requireActive(session);

        String symbol = request.symbol();
        GamePosition position = positionRepository.findBySessionIdAndSymbol(sessionId, symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No open position in " + symbol + " to insure"));
        if (position.isInsured()) {
            throw new GamePositionAlreadyInsuredException(sessionId, symbol);
        }

        BigDecimal currentPrice = marketService.currentPrice(session.getDifficulty(), symbol).orElse(position.getAvgCost());
        BigDecimal positionValue = currentPrice.multiply(position.getQuantity());
        BigDecimal premium = positionValue.multiply(INSURANCE_PREMIUM_PERCENT_OF_POSITION_VALUE)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (session.getCash().compareTo(premium) < 0) {
            throw new GameInsufficientFundsException(sessionId, premium, session.getCash());
        }

        session.setCash(session.getCash().subtract(premium));
        position.setInsured(true);
        position.setInsuranceFloorPrice(position.getAvgCost().multiply(INSURANCE_FLOOR_PERCENT_OF_AVG_COST));
        sessionRepository.save(session);
        positionRepository.save(position);

        log.info("Game insurance purchased: sessionId={}, symbol={}, premium={}, floorPrice={}",
                sessionId, symbol, premium, position.getInsuranceFloorPrice());
        return toResponse(session);
    }

    @Override
    @Transactional
    public GameSessionResponse takeLoan(UUID sessionId, GameLoanRequest request, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        requireActive(session);

        BigDecimal existingOutstanding = loanRepository.findBySessionId(sessionId).stream()
                .map(GameLoan::getOutstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal requestedTotalExposure = existingOutstanding.add(request.amount());
        BigDecimal netWorth = computeNetWorth(session);
        BigDecimal maxAllowed = netWorth.multiply(MAX_LOAN_TO_NET_WORTH_MULTIPLIER);
        if (requestedTotalExposure.compareTo(maxAllowed) > 0) {
            throw new GameLoanDeclinedException(sessionId, requestedTotalExposure, netWorth, maxAllowed);
        }

        session.setCash(session.getCash().add(request.amount()));
        sessionRepository.save(session);

        Instant now = Instant.now();
        loanRepository.save(GameLoan.builder()
                .gameLoanId(UUID.randomUUID())
                .sessionId(sessionId)
                .principal(request.amount())
                .outstandingPrincipal(request.amount())
                .accruedInterest(BigDecimal.ZERO)
                .rateAnnualPercent(session.getDifficulty().getLoanRateAnnualPercent())
                .originatedAt(now)
                .lastAccrualAt(now)
                .build());

        log.info("Game loan taken: sessionId={}, amount={}, rate={}", sessionId, request.amount(), session.getDifficulty().getLoanRateAnnualPercent());
        return toResponse(session);
    }

    @Override
    @Transactional
    public GameSessionResponse repayLoan(UUID sessionId, UUID gameLoanId, GameLoanRepayRequest request, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        requireActive(session);

        GameLoan loan = loanRepository.findById(gameLoanId)
                .filter(l -> l.getSessionId().equals(sessionId))
                .orElseThrow(() -> new GameLoanNotFoundException(gameLoanId));

        // Settle whatever's accrued since the last touch into a fixed number before applying the
        // payment — otherwise the still-ticking live delta would make interest "reappear" the
        // instant after paying it off, computed from a stale lastAccrualAt.
        settleAccrual(loan);

        BigDecimal totalOwedNow = loan.getOutstandingPrincipal().add(loan.getAccruedInterest());
        BigDecimal payment = request.amount().min(totalOwedNow);

        if (session.getCash().compareTo(payment) < 0) {
            throw new GameInsufficientFundsException(sessionId, payment, session.getCash());
        }
        session.setCash(session.getCash().subtract(payment));

        BigDecimal remaining = payment;
        BigDecimal interestPaid = remaining.min(loan.getAccruedInterest());
        loan.setAccruedInterest(loan.getAccruedInterest().subtract(interestPaid));
        remaining = remaining.subtract(interestPaid);
        BigDecimal principalPaid = remaining.min(loan.getOutstandingPrincipal());
        loan.setOutstandingPrincipal(loan.getOutstandingPrincipal().subtract(principalPaid));

        loanRepository.save(loan);
        sessionRepository.save(session);

        log.info("Game loan repayment: sessionId={}, gameLoanId={}, payment={}, outstandingPrincipal={}, accruedInterest={}",
                sessionId, gameLoanId, payment, loan.getOutstandingPrincipal(), loan.getAccruedInterest());

        evaluate(session);
        return toResponse(session);
    }

    @Override
    @Transactional
    public GameSessionResponse placeTrade(UUID sessionId, GameTradeRequest request, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        requireActive(session);

        String symbol = request.symbol();
        BigDecimal price = marketService.currentPrice(session.getDifficulty(), symbol)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown Game Mode symbol: " + symbol));
        BigDecimal quantity = request.quantity();
        BigDecimal notional = price.multiply(quantity);
        boolean reactionTrade = isReactionTrade(session.getDifficulty(), symbol);
        BigDecimal fee = reactionTrade ? BigDecimal.ZERO : notional.multiply(session.getDifficulty().getFeeRate()).setScale(2, RoundingMode.HALF_UP);

        GameTrade trade = request.side() == OrderSide.BUY
                ? executeBuy(session, symbol, quantity, price, notional, fee)
                : executeSell(session, symbol, quantity, price, notional, fee);

        sessionRepository.save(session);
        tradeRepository.save(trade);
        log.info("Game trade: sessionId={}, symbol={}, side={}, quantity={}, price={}, fee={}, realizedPnl={}, reactionTrade={}",
                sessionId, symbol, request.side(), quantity, price, fee, trade.getRealizedPnl(), reactionTrade);

        evaluate(session);
        return toResponse(session);
    }

    /** True if {@code symbol} had a market event (crash, rally, or chaos spike) within the last {@link #REACTION_WINDOW_SECONDS} — the reward for reading the news ticker and acting on it fast. */
    private boolean isReactionTrade(GameDifficulty difficulty, String symbol) {
        Instant cutoff = Instant.now().minusSeconds(REACTION_WINDOW_SECONDS);
        return marketService.recentEvents(difficulty).stream()
                .anyMatch(event -> event.symbol().equals(symbol) && event.occurredAt().isAfter(cutoff));
    }

    /**
     * Current run of consecutive profitable closed trades, most-recent-first — resets to 0 the
     * moment a closed trade loses money. Purely a live read of already-recorded {@code GameTrade}
     * rows (only SELLs carry a non-null {@code realizedPnl}; BUYs don't close anything and are
     * skipped rather than treated as streak-breakers), no new persistence needed.
     */
    private int computeCurrentStreak(UUID sessionId) {
        int streak = 0;
        for (GameTrade trade : tradeRepository.findBySessionIdOrderByCreatedAtDesc(sessionId)) {
            if (trade.getRealizedPnl() == null) {
                continue;
            }
            if (trade.getRealizedPnl().signum() <= 0) {
                break;
            }
            streak++;
        }
        return streak;
    }

    /** The longest such run reached at any point during the session — read oldest-to-newest, unlike {@link #computeCurrentStreak}'s most-recent-first live read. Used only for the "On Fire" achievement at debrief time. */
    private int computeBestStreak(List<GameTrade> tradesOldestFirst) {
        int best = 0;
        int running = 0;
        for (GameTrade trade : tradesOldestFirst) {
            if (trade.getRealizedPnl() == null) {
                continue;
            }
            if (trade.getRealizedPnl().signum() > 0) {
                running++;
                best = Math.max(best, running);
            } else {
                running = 0;
            }
        }
        return best;
    }

    private GameTrade executeBuy(GameSession session, String symbol, BigDecimal quantity, BigDecimal price, BigDecimal notional, BigDecimal fee) {
        BigDecimal totalCost = notional.add(fee);
        if (session.getCash().compareTo(totalCost) < 0) {
            throw new GameInsufficientFundsException(session.getSessionId(), totalCost, session.getCash());
        }
        session.setCash(session.getCash().subtract(totalCost));

        GamePosition position = positionRepository.findBySessionIdAndSymbol(session.getSessionId(), symbol)
                .orElseGet(() -> GamePosition.builder()
                        .positionId(UUID.randomUUID())
                        .sessionId(session.getSessionId())
                        .symbol(symbol)
                        .quantity(BigDecimal.ZERO)
                        .avgCost(BigDecimal.ZERO)
                        .dividendLastAccrualAt(Instant.now())
                        .build());
        BigDecimal existingNotional = position.getAvgCost().multiply(position.getQuantity());
        BigDecimal newQuantity = position.getQuantity().add(quantity);
        position.setAvgCost(existingNotional.add(notional).divide(newQuantity, 8, RoundingMode.HALF_UP));
        position.setQuantity(newQuantity);
        positionRepository.save(position);

        return GameTrade.builder()
                .tradeId(UUID.randomUUID()).sessionId(session.getSessionId()).symbol(symbol).side(OrderSide.BUY)
                .quantity(quantity).price(price).fee(fee).realizedPnl(null).createdAt(Instant.now())
                .build();
    }

    private GameTrade executeSell(GameSession session, String symbol, BigDecimal quantity, BigDecimal price, BigDecimal notional, BigDecimal fee) {
        GamePosition position = positionRepository.findBySessionIdAndSymbol(session.getSessionId(), symbol)
                .orElseThrow(() -> new GameInsufficientPositionException(session.getSessionId(), symbol, quantity, BigDecimal.ZERO));
        if (position.getQuantity().compareTo(quantity) < 0) {
            throw new GameInsufficientPositionException(session.getSessionId(), symbol, quantity, position.getQuantity());
        }

        // Insurance's actual payout happens here: if the real price has fallen below the insured
        // floor, the sale settles at the floor instead — the insurer covers the gap.
        BigDecimal effectivePrice = effectivePrice(position, price);
        BigDecimal effectiveNotional = effectivePrice.multiply(quantity);
        BigDecimal proceeds = effectiveNotional.subtract(fee);
        session.setCash(session.getCash().add(proceeds));

        BigDecimal realizedPnl = effectivePrice.subtract(position.getAvgCost()).multiply(quantity).subtract(fee);
        BigDecimal remainingQuantity = position.getQuantity().subtract(quantity);
        if (remainingQuantity.signum() == 0) {
            positionRepository.delete(position);
        } else {
            position.setQuantity(remainingQuantity);
            positionRepository.save(position);
        }

        return GameTrade.builder()
                .tradeId(UUID.randomUUID()).sessionId(session.getSessionId()).symbol(symbol).side(OrderSide.SELL)
                .quantity(quantity).price(price).fee(fee).realizedPnl(realizedPnl).createdAt(Instant.now())
                .build();
    }

    @Override
    @Transactional
    public GameSessionResponse depositToSavings(UUID sessionId, GameSavingsRequest request, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        requireActive(session);

        if (session.getCash().compareTo(request.amount()) < 0) {
            throw new GameInsufficientFundsException(sessionId, request.amount(), session.getCash());
        }
        settleSavingsAccrual(session);
        session.setCash(session.getCash().subtract(request.amount()));
        session.setSavingsBalance(session.getSavingsBalance().add(request.amount()));
        sessionRepository.save(session);

        log.info("Game savings deposit: sessionId={}, amount={}, savingsBalance={}", sessionId, request.amount(), session.getSavingsBalance());
        return toResponse(session);
    }

    @Override
    @Transactional
    public GameSessionResponse withdrawFromSavings(UUID sessionId, GameSavingsRequest request, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        requireActive(session);

        settleSavingsAccrual(session);
        if (session.getSavingsBalance().compareTo(request.amount()) < 0) {
            throw new GameInsufficientSavingsException(sessionId, request.amount(), session.getSavingsBalance());
        }
        session.setSavingsBalance(session.getSavingsBalance().subtract(request.amount()));
        session.setCash(session.getCash().add(request.amount()));
        sessionRepository.save(session);

        log.info("Game savings withdrawal: sessionId={}, amount={}, savingsBalance={}", sessionId, request.amount(), session.getSavingsBalance());
        return toResponse(session);
    }

    @Override
    @Transactional(readOnly = true)
    public List<GameTradeResponse> listTrades(UUID sessionId, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        return tradeRepository.findBySessionIdOrderByCreatedAtDesc(sessionId).stream().map(this::toTradeResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GameStatsResponse getStats(String clientId, CallerPrincipal caller) {
        caller.requireOwner(clientId);
        List<GameSession> finished = sessionRepository.findByClientIdAndStatusNot(clientId, GameStatus.IN_PROGRESS);

        int totalGames = finished.size();
        int wins = (int) finished.stream().filter(s -> s.getStatus() == GameStatus.WON).count();
        double winRatePercent = totalGames == 0 ? 0.0 : (wins * 100.0) / totalGames;

        List<UUID> sessionIds = finished.stream().map(GameSession::getSessionId).toList();
        BigDecimal bestTradePnl = sessionIds.isEmpty() ? null
                : tradeRepository.findBestRealizedTrades(sessionIds).stream().findFirst().map(GameTrade::getRealizedPnl).orElse(null);

        Map<GameDifficulty, List<GameSession>> byDifficulty = finished.stream().collect(Collectors.groupingBy(GameSession::getDifficulty));
        List<GameDifficultyStat> perDifficulty = Arrays.stream(GameDifficulty.values())
                .map(d -> {
                    List<GameSession> sessions = byDifficulty.getOrDefault(d, List.of());
                    int gamesPlayed = sessions.size();
                    int diffWins = (int) sessions.stream().filter(s -> s.getStatus() == GameStatus.WON).count();
                    BigDecimal bestNetWorth = sessions.stream()
                            .map(GameSession::getFinalNetWorth).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
                    return new GameDifficultyStat(d, gamesPlayed, diffWins, bestNetWorth);
                })
                .filter(stat -> stat.gamesPlayed() > 0)
                .toList();

        return new GameStatsResponse(clientId, totalGames, wins, winRatePercent, bestTradePnl, perDifficulty);
    }

    @Override
    @Transactional(readOnly = true)
    public GameLeaderboardResponse getLeaderboard(GameDifficulty difficulty, GameLeaderboardSortBy sortBy, String viewerClientId) {
        GameLeaderboardSortBy effectiveSortBy = sortBy != null ? sortBy : GameLeaderboardSortBy.NET_WORTH;
        List<GameSession> top = effectiveSortBy == GameLeaderboardSortBy.FASTEST_WIN
                ? sessionRepository.findByDifficultyAndStatus(difficulty, GameStatus.WON).stream()
                        .sorted(Comparator.comparing(s -> Duration.between(s.getStartedAt(), s.getFinishedAt())))
                        .limit(10)
                        .toList()
                : sessionRepository.findTop10ByDifficultyAndFinalNetWorthIsNotNullOrderByFinalNetWorthDesc(difficulty);

        List<GameLeaderboardEntry> entries = new ArrayList<>();
        for (int i = 0; i < top.size(); i++) {
            GameSession session = top.get(i);
            long durationSeconds = Duration.between(session.getStartedAt(), session.getFinishedAt()).getSeconds();
            entries.add(new GameLeaderboardEntry(
                    i + 1, session.getFinalNetWorth(), durationSeconds, session.getStatus(), session.getClientId().equals(viewerClientId)));
        }
        return new GameLeaderboardResponse(difficulty, effectiveSortBy, entries);
    }

    @Override
    @Transactional
    public GameDebriefResponse getDebrief(UUID sessionId, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        if (session.getStatus() == GameStatus.IN_PROGRESS) {
            throw new GameSessionStillInProgressException(sessionId);
        }

        List<GameTrade> trades = tradeRepository.findBySessionIdOrderByCreatedAtDesc(sessionId).stream()
                .sorted(Comparator.comparing(GameTrade::getCreatedAt))
                .toList();
        List<GamePosition> positions = positionRepository.findBySessionId(sessionId);
        List<GameLoan> loans = loanRepository.findBySessionId(sessionId);

        List<GameSymbolPerformanceResponse> symbolPerformance = buildSymbolPerformance(session, trades, positions);
        String narrative = buildNarrative(session, trades, loans, symbolPerformance);
        String fallbackSummary = buildFallbackSummary(session, symbolPerformance, loans);
        List<GameAchievementResponse> achievements = computeAchievements(session, trades, loans);

        GameDebriefResult result = gameCoach.debrief(new GameDebriefContext(narrative, fallbackSummary));
        log.info("Game debrief generated: sessionId={}, aiGenerated={}", sessionId, result.aiGenerated());
        return new GameDebriefResponse(result.summary(), result.aiGenerated(), symbolPerformance, achievements);
    }

    /**
     * Deterministic, rule-based badges read off already-recorded trade/loan/session data — never a
     * new gameplay rule, never AI-decided, and a session can earn any number of them (including
     * zero). Kept here rather than as a separate service since every input already lives in this
     * class's existing debrief data — a new class would just be an extra hop to the same fields.
     */
    private List<GameAchievementResponse> computeAchievements(GameSession session, List<GameTrade> trades, List<GameLoan> loans) {
        List<GameAchievementResponse> achievements = new ArrayList<>();
        boolean won = session.getStatus() == GameStatus.WON;
        GameDifficulty difficulty = session.getDifficulty();

        if (won && loans.isEmpty()) {
            achievements.add(new GameAchievementResponse("Debt Free", "Reached the goal without ever taking a loan."));
        }
        if (won && loans.stream().anyMatch(l -> l.getPrincipal().compareTo(difficulty.getStartingCash()) > 0)) {
            achievements.add(new GameAchievementResponse("Leveraged Up", "Won after borrowing more than your entire starting cash."));
        }
        if (trades.size() >= 10) {
            achievements.add(new GameAchievementResponse("Day Trader", "Placed 10 or more trades in a single session."));
        }
        BigDecimal bigWinThreshold = difficulty.getStartingCash().multiply(new BigDecimal("0.25"));
        if (trades.stream().anyMatch(t -> t.getRealizedPnl() != null && t.getRealizedPnl().compareTo(bigWinThreshold) > 0)) {
            achievements.add(new GameAchievementResponse("Home Run", "Banked a single trade worth over 25% of your starting cash."));
        }
        if (won && session.getFinishedAt() != null
                && session.getFinishedAt().isBefore(session.getStartedAt().plus(Duration.between(session.getStartedAt(), session.getEndsAt()).dividedBy(2)))) {
            achievements.add(new GameAchievementResponse("Speed Runner", "Reached the goal with more than half the clock still left."));
        }
        List<BigDecimal> realizedResults = trades.stream().map(GameTrade::getRealizedPnl).filter(Objects::nonNull).toList();
        if (realizedResults.size() >= 3 && realizedResults.stream().allMatch(pnl -> pnl.signum() >= 0)) {
            achievements.add(new GameAchievementResponse("Perfectionist", "Every closed trade this session was profitable."));
        }
        if (session.getStatus() == GameStatus.LOST_BANKRUPT) {
            achievements.add(new GameAchievementResponse("Lesson Learned", "Went bankrupt — the loan interest and the market both bite back."));
        }
        if (computeBestStreak(trades) >= HOT_STREAK_ACHIEVEMENT_THRESHOLD) {
            achievements.add(new GameAchievementResponse("On Fire", HOT_STREAK_ACHIEVEMENT_THRESHOLD + " or more profitable trades in a row."));
        }
        return achievements;
    }

    /** One row per symbol ever traded or still held — realized P&L from closed sells, unrealized from whatever's still open. */
    private List<GameSymbolPerformanceResponse> buildSymbolPerformance(GameSession session, List<GameTrade> trades, List<GamePosition> positions) {
        Map<String, GamePosition> positionBySymbol = positions.stream().collect(Collectors.toMap(GamePosition::getSymbol, p -> p));
        Map<String, BigDecimal> realizedBySymbol = trades.stream()
                .filter(t -> t.getRealizedPnl() != null)
                .collect(Collectors.groupingBy(GameTrade::getSymbol, Collectors.reducing(BigDecimal.ZERO, GameTrade::getRealizedPnl, BigDecimal::add)));

        Set<String> symbols = new TreeSet<>();
        symbols.addAll(realizedBySymbol.keySet());
        symbols.addAll(positionBySymbol.keySet());

        return symbols.stream()
                .map(symbol -> {
                    BigDecimal realizedPnl = realizedBySymbol.getOrDefault(symbol, BigDecimal.ZERO);
                    GamePosition position = positionBySymbol.get(symbol);
                    BigDecimal quantityHeld = position != null ? position.getQuantity() : BigDecimal.ZERO;
                    BigDecimal unrealizedPnl = BigDecimal.ZERO;
                    if (position != null) {
                        BigDecimal currentPrice = effectivePrice(position, marketService.currentPrice(session.getDifficulty(), symbol).orElse(position.getAvgCost()));
                        unrealizedPnl = currentPrice.subtract(position.getAvgCost()).multiply(position.getQuantity());
                    }
                    return new GameSymbolPerformanceResponse(symbol, realizedPnl, unrealizedPnl, realizedPnl.add(unrealizedPnl), quantityHeld);
                })
                .sorted(Comparator.comparing(GameSymbolPerformanceResponse::totalPnl).reversed())
                .toList();
    }

    /** Everything Claude needs to write a grounded debrief: the rules of the tier played, the outcome, and the full trade/loan history. */
    private String buildNarrative(GameSession session, List<GameTrade> trades, List<GameLoan> loans, List<GameSymbolPerformanceResponse> symbolPerformance) {
        GameDifficulty d = session.getDifficulty();
        StringBuilder sb = new StringBuilder();
        sb.append("This is Game Mode, a practice trading game with fake money. Difficulty: ").append(d.getDisplayName())
                .append(". Rules: start with ").append(money(d.getStartingCash())).append(" cash, reach a net worth of ")
                .append(money(d.getGoalAmount())).append(" within ").append(d.getDurationMinutes())
                .append(" minutes to win, going bankrupt (net worth below zero) loses immediately. Loan interest accrues at ")
                .append(d.getLoanRateAnnualPercent()).append("% of the outstanding balance per MINUTE it's left unpaid (not per year — ")
                .append("this is deliberately fast so interest is a felt cost within a short session). Trading fee is ")
                .append(d.getFeeRate()).append(" of notional per trade.\n\n");

        sb.append("Outcome: ").append(session.getStatus()).append(", final net worth ")
                .append(money(session.getFinalNetWorth())).append(" against a goal of ").append(money(d.getGoalAmount())).append(".\n\n");

        sb.append("Trades, in order:\n");
        if (trades.isEmpty()) {
            sb.append("- none — no trades were placed this session\n");
        }
        for (GameTrade t : trades) {
            sb.append("- ").append(t.getSide()).append(' ').append(t.getQuantity()).append(' ').append(t.getSymbol()).append(" @ ").append(t.getPrice());
            if (t.getRealizedPnl() != null) {
                sb.append(" (realized P&L ").append(money(t.getRealizedPnl())).append(')');
            }
            sb.append('\n');
        }

        sb.append("\nLoans taken:\n");
        if (loans.isEmpty()) {
            sb.append("- none — no loans were taken this session\n");
        }
        for (GameLoan loan : loans) {
            BigDecimal owed = totalOwed(loan);
            sb.append("- borrowed ").append(money(loan.getPrincipal())).append(" at ").append(loan.getRateAnnualPercent())
                    .append("%/minute; ").append(owed.signum() > 0 ? "still owed " + money(owed) + " at session end" : "fully repaid").append('\n');
        }

        sb.append("\nFinal per-symbol P&L:\n");
        for (GameSymbolPerformanceResponse p : symbolPerformance) {
            sb.append("- ").append(p.symbol()).append(": total P&L ").append(money(p.totalPnl()))
                    .append(p.quantityHeld().signum() > 0 ? " (still holding " + p.quantityHeld() + " shares, unrealized)" : " (fully closed out)")
                    .append('\n');
        }

        return sb.toString();
    }

    /** Used verbatim when Claude is unavailable, and as the AI's starting point otherwise — always a real, specific summary, never a placeholder. */
    private String buildFallbackSummary(GameSession session, List<GameSymbolPerformanceResponse> symbolPerformance, List<GameLoan> loans) {
        String outcome = switch (session.getStatus()) {
            case WON -> "You won";
            case LOST_BANKRUPT -> "You went bankrupt";
            case LOST_TIME -> "You ran out of time";
            case IN_PROGRESS -> "The session ended";
        };
        StringBuilder sb = new StringBuilder(outcome)
                .append(" with a net worth of ").append(money(session.getFinalNetWorth()))
                .append(" against a goal of ").append(money(session.getDifficulty().getGoalAmount())).append(". ");

        Optional<GameSymbolPerformanceResponse> best = symbolPerformance.stream().max(Comparator.comparing(GameSymbolPerformanceResponse::totalPnl));
        Optional<GameSymbolPerformanceResponse> worst = symbolPerformance.stream().min(Comparator.comparing(GameSymbolPerformanceResponse::totalPnl));
        best.filter(b -> b.totalPnl().signum() > 0)
                .ifPresent(b -> sb.append("Your best position was ").append(b.symbol()).append(" at ").append(money(b.totalPnl())).append(". "));
        worst.filter(w -> w.totalPnl().signum() < 0)
                .ifPresent(w -> sb.append("Your worst position was ").append(w.symbol()).append(" at ").append(money(w.totalPnl())).append(". "));

        if (!loans.isEmpty()) {
            BigDecimal stillOwed = loans.stream().map(this::totalOwed).reduce(BigDecimal.ZERO, BigDecimal::add);
            sb.append(stillOwed.signum() > 0
                    ? "You still owed " + money(stillOwed) + " in loans at the end, which dragged down your net worth."
                    : "All loans were repaid by the end.");
        }
        return sb.toString();
    }

    private String money(BigDecimal amount) {
        return "£" + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Flips status to WON/LOST_BANKRUPT/LOST_TIME if the session has actually ended since it was last read — see the class javadoc. */
    void evaluate(GameSession session) {
        if (session.getStatus() != GameStatus.IN_PROGRESS) {
            return;
        }
        settleDividends(session);
        settleTax(session);
        settleAdvisorTip(session);
        BigDecimal netWorth = computeNetWorth(session);
        Instant now = Instant.now();

        GameStatus newStatus = null;
        if (netWorth.compareTo(session.getDifficulty().getGoalAmount()) >= 0) {
            newStatus = GameStatus.WON;
        } else if (netWorth.signum() < 0) {
            newStatus = GameStatus.LOST_BANKRUPT;
        } else if (!now.isBefore(session.getEndsAt())) {
            newStatus = GameStatus.LOST_TIME;
        }

        if (newStatus != null) {
            session.setStatus(newStatus);
            session.setFinishedAt(now);
            session.setFinalNetWorth(netWorth);
            sessionRepository.save(session);
            log.info("Game session ended: sessionId={}, status={}, finalNetWorth={}", session.getSessionId(), newStatus, netWorth);
        }
    }

    BigDecimal computeNetWorth(GameSession session) {
        BigDecimal positionsValue = positionRepository.findBySessionId(session.getSessionId()).stream()
                .map(p -> effectivePrice(p, marketService.currentPrice(session.getDifficulty(), p.getSymbol()).orElse(p.getAvgCost())).multiply(p.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loansOwed = loanRepository.findBySessionId(session.getSessionId()).stream()
                .map(this::totalOwed)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return session.getCash().add(positionsValue).add(currentSavingsValue(session)).subtract(loansOwed);
    }

    /**
     * The price a position is actually marked/settled at: the real market price, unless the
     * position is insured and the real price has fallen below the floor it locked in, in which
     * case the floor wins. This single helper is the one place the insurance floor is enforced —
     * every valuation and sale path routes through it rather than reading the raw market price
     * directly.
     */
    private BigDecimal effectivePrice(GamePosition position, BigDecimal marketPrice) {
        return position.isInsured() ? marketPrice.max(position.getInsuranceFloorPrice()) : marketPrice;
    }

    /**
     * Live savings value right now: whatever's already settled (persisted balance) plus the live
     * delta accrued since {@code savingsLastAccrualAt} — same "don't write on every poll" pattern
     * as {@link #interestOwed}. Null-safe against {@code GameSession} instances built without the
     * savings fields set (pre-existing unit tests construct sessions this way; a real session
     * created via {@link #startSession} always has both fields populated).
     */
    BigDecimal currentSavingsValue(GameSession session) {
        BigDecimal balance = session.getSavingsBalance() != null ? session.getSavingsBalance() : BigDecimal.ZERO;
        return balance.add(pendingSavingsInterest(session, balance));
    }

    private BigDecimal pendingSavingsInterest(GameSession session, BigDecimal balance) {
        if (balance.signum() <= 0 || session.getSavingsLastAccrualAt() == null) {
            return BigDecimal.ZERO;
        }
        double compressedDaysElapsed = Duration.between(session.getSavingsLastAccrualAt(), Instant.now()).toMillis() / 60_000.0
                * SAVINGS_INTEREST_DAYS_PER_MINUTE;
        if (compressedDaysElapsed <= 0) {
            return BigDecimal.ZERO;
        }
        // Same day-based formula as GameLoan's pendingInterest, each elapsed minute standing in
        // for SAVINGS_INTEREST_DAYS_PER_MINUTE compressed "days" — see that field's javadoc for why.
        return balance
                .multiply(SAVINGS_RATE_ANNUAL_PERCENT)
                .multiply(BigDecimal.valueOf(compressedDaysElapsed))
                .divide(BigDecimal.valueOf(100L * DAYS_PER_YEAR), 8, RoundingMode.HALF_UP);
    }

    /** Folds the live pending interest into the persisted balance and resets the accrual clock — called before a deposit/withdrawal needs an exact, stable balance to move money against. */
    private void settleSavingsAccrual(GameSession session) {
        session.setSavingsBalance(currentSavingsValue(session));
        session.setSavingsLastAccrualAt(Instant.now());
    }

    /**
     * Sweeps any pending dividend on every stock position straight into cash — called as the
     * first line inside {@link #evaluate}, before {@link #computeNetWorth}, so a dividend lands
     * before a same-request SELL could delete the position it's paid against. Unlike savings/loan
     * interest, there's no separate "live value" to compute on read: dividends settle directly
     * into spendable cash, so a plain read of {@code cash} already reflects them.
     */
    void settleDividends(GameSession session) {
        Instant now = Instant.now();
        BigDecimal totalDividend = BigDecimal.ZERO;
        for (GamePosition position : positionRepository.findBySessionId(session.getSessionId())) {
            if (!marketService.isStockSymbol(position.getSymbol()) || position.getDividendLastAccrualAt() == null) {
                continue;
            }
            double compressedDaysElapsed = Duration.between(position.getDividendLastAccrualAt(), now).toMillis() / 60_000.0 * DIVIDEND_DAYS_PER_MINUTE;
            if (compressedDaysElapsed <= 0) {
                continue;
            }
            BigDecimal currentPrice = marketService.currentPrice(session.getDifficulty(), position.getSymbol()).orElse(position.getAvgCost());
            BigDecimal marketValue = currentPrice.multiply(position.getQuantity());
            BigDecimal dividend = marketValue
                    .multiply(DIVIDEND_YIELD_ANNUAL_PERCENT)
                    .multiply(BigDecimal.valueOf(compressedDaysElapsed))
                    .divide(BigDecimal.valueOf(100L * DAYS_PER_YEAR), 8, RoundingMode.HALF_UP);
            totalDividend = totalDividend.add(dividend);
            position.setDividendLastAccrualAt(now);
            positionRepository.save(position);
        }
        if (totalDividend.signum() > 0) {
            session.setCash(session.getCash().add(totalDividend));
            BigDecimal existingTotal = session.getTotalDividendsPaid() != null ? session.getTotalDividendsPaid() : BigDecimal.ZERO;
            session.setTotalDividendsPaid(existingTotal.add(totalDividend));
        }
    }

    /**
     * A discrete periodic deduction — not a live-computed value like savings/loan interest — off a
     * running high-water mark of already-taxed realized P&L, so a later loss never claws back tax
     * already paid and the same banked profit is never taxed twice. Deliberately taxes only
     * trade-based capital gains, not dividend income (a documented simplification, not an
     * oversight) — settled at most once every {@code TAX_SETTLEMENT_INTERVAL_SECONDS}, since
     * hitting the trade table on every single poll would be pure overhead for a value that only
     * actually changes when new profit is banked.
     */
    void settleTax(GameSession session) {
        Instant now = Instant.now();
        // A null clock (a session built before this field existed, or in a test) starts the clock
        // now rather than settling immediately — mirrors the migration's own `DEFAULT now()`,
        // so nothing gets backdated tax for time before this mechanic existed. Once set, a
        // not-yet-due check must NOT touch the clock (unlike the null branch) — evaluate() runs on
        // every poll (the frontend refetches every 5s), so rewriting the clock here would restart
        // the interval on each request and the 60s mark would never actually be reached.
        if (session.getTaxLastSettledAt() == null) {
            session.setTaxLastSettledAt(now);
            return;
        }
        if (session.getTaxLastSettledAt().plusSeconds(TAX_SETTLEMENT_INTERVAL_SECONDS).isAfter(now)) {
            return;
        }

        BigDecimal totalRealized = tradeRepository.sumRealizedPnl(session.getSessionId());
        BigDecimal alreadyTaxed = session.getTotalRealizedPnlTaxed() != null ? session.getTotalRealizedPnlTaxed() : BigDecimal.ZERO;
        BigDecimal newlyTaxable = totalRealized.subtract(alreadyTaxed).max(BigDecimal.ZERO);
        if (newlyTaxable.signum() > 0) {
            BigDecimal taxOwed = newlyTaxable.multiply(TAX_RATE_PERCENT).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            session.setCash(session.getCash().subtract(taxOwed));
            BigDecimal existingTaxPaid = session.getTotalTaxPaid() != null ? session.getTotalTaxPaid() : BigDecimal.ZERO;
            session.setTotalTaxPaid(existingTaxPaid.add(taxOwed));
            session.setTotalRealizedPnlTaxed(alreadyTaxed.max(totalRealized));
        }
        session.setTaxLastSettledAt(now);
    }

    /**
     * Picks (and persists) a fresh advisor tip once {@code ADVISOR_TIP_INTERVAL_SECONDS} has
     * elapsed since the last one — unlike the savings/loan interest "live, recompute on every
     * read" pattern, a tip has to be a stable, named call the player can actually decide whether
     * to act on, so it's written down rather than recomputed fresh on every poll. No-op if no
     * advisor is hired.
     */
    void settleAdvisorTip(GameSession session) {
        if (!session.isAdvisorHired()) {
            return;
        }
        Instant now = Instant.now();
        if (session.getAdvisorLastTipAt() != null && session.getAdvisorLastTipAt().plusSeconds(ADVISOR_TIP_INTERVAL_SECONDS).isAfter(now)) {
            return;
        }

        List<String> symbols = new ArrayList<>(marketService.currentPrices(session.getDifficulty()).keySet());
        if (symbols.isEmpty()) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String symbol = symbols.get(random.nextInt(symbols.size()));
        boolean regimeUp = marketService.currentTrendUp(session.getDifficulty(), symbol).orElse(random.nextBoolean());
        boolean tipMatchesRegime = random.nextDouble() < ADVISOR_TIP_ACCURACY;
        boolean tipUp = tipMatchesRegime ? regimeUp : !regimeUp;
        OrderSide tipSide = tipUp ? OrderSide.BUY : OrderSide.SELL;

        session.setAdvisorTipSymbol(symbol);
        session.setAdvisorTipSide(tipSide);
        session.setAdvisorLastTipAt(now);
        sessionRepository.save(session);
    }

    private BigDecimal totalOwed(GameLoan loan) {
        return loan.getOutstandingPrincipal().add(interestOwed(loan));
    }

    /**
     * Interest owed right now: whatever's already been settled (persisted, from a previous
     * repayment) plus the live delta accrued since {@code lastAccrualAt} — not yet written down,
     * since a plain read (a session poll) shouldn't cause a database write every few seconds.
     */
    BigDecimal interestOwed(GameLoan loan) {
        return loan.getAccruedInterest().add(pendingInterest(loan));
    }

    /**
     * Mirrors the real {@code Loan}'s day-based simple-interest formula (see
     * {@code LoanServiceImplTest}) — {@code principal * rate * days / (100 * DAYS_PER_YEAR)} —
     * but with each elapsed <b>minute</b> standing in for {@link #LOAN_INTEREST_DAYS_PER_MINUTE}
     * compressed "days," since a Game Mode session lasts at most 30 minutes and using the loan's
     * real wall-clock elapsed time would accrue only fractions of a penny for the entire game
     * (confirmed by playing it — a £5,000 loan at 20% APR held for the full 30 minutes accrued
     * about 6p), defeating the point of modeling interest as a felt cost. An earlier version of
     * this method dropped the annualization divisor entirely and applied the annual rate directly
     * per minute, which was the opposite problem — the same £5,000/20% loan accrued £30,000 in 30
     * minutes. A 1-day-per-minute compression landed in between (~£82 for that example) but
     * players reported it was still too small and too slow to matter, so it's now scaled by
     * {@code LOAN_INTEREST_DAYS_PER_MINUTE} (~£493 for that same £5,000/20%/30-minute example).
     */
    private BigDecimal pendingInterest(GameLoan loan) {
        double compressedDaysElapsed = Duration.between(loan.getLastAccrualAt(), Instant.now()).toMillis() / 60_000.0
                * LOAN_INTEREST_DAYS_PER_MINUTE;
        if (compressedDaysElapsed <= 0) {
            return BigDecimal.ZERO;
        }
        return loan.getOutstandingPrincipal()
                .multiply(loan.getRateAnnualPercent())
                .multiply(BigDecimal.valueOf(compressedDaysElapsed))
                .divide(BigDecimal.valueOf(100L * DAYS_PER_YEAR), 8, RoundingMode.HALF_UP);
    }

    /** Folds the live pending delta into the persisted {@code accruedInterest} and resets the accrual clock — called before a repayment needs an exact, stable number to apply a payment against. */
    private void settleAccrual(GameLoan loan) {
        loan.setAccruedInterest(loan.getAccruedInterest().add(pendingInterest(loan)));
        loan.setLastAccrualAt(Instant.now());
    }

    private GameSessionResponse toResponse(GameSession session) {
        List<GamePositionResponse> positions = positionRepository.findBySessionId(session.getSessionId()).stream()
                .map(p -> {
                    BigDecimal currentPrice = marketService.currentPrice(session.getDifficulty(), p.getSymbol()).orElse(p.getAvgCost());
                    // marketValue/unrealizedPnl use the insured floor (if any) — the player sees
                    // their protected value, not a scary real-time dip below what they paid for.
                    BigDecimal valuationPrice = effectivePrice(p, currentPrice);
                    BigDecimal marketValue = valuationPrice.multiply(p.getQuantity());
                    BigDecimal unrealizedPnl = valuationPrice.subtract(p.getAvgCost()).multiply(p.getQuantity());
                    return new GamePositionResponse(
                            p.getSymbol(), p.getQuantity(), p.getAvgCost(), currentPrice, marketValue, unrealizedPnl, p.isInsured(), p.getInsuranceFloorPrice());
                })
                .toList();

        // A loan paid off in full (nothing outstanding, nothing accrued) drops off the list —
        // there's nothing left to show or repay, same as the real Loan's PAID_OFF status just
        // being filtered out of a client-facing summary rather than a status enum here.
        List<GameLoanResponse> loans = loanRepository.findBySessionId(session.getSessionId()).stream()
                .filter(l -> l.getOutstandingPrincipal().signum() > 0 || interestOwed(l).signum() > 0)
                .map(l -> new GameLoanResponse(
                        l.getGameLoanId(), l.getPrincipal(), l.getOutstandingPrincipal(), interestOwed(l), l.getRateAnnualPercent(), l.getOriginatedAt()))
                .toList();

        BigDecimal netWorth = session.getStatus() == GameStatus.IN_PROGRESS ? computeNetWorth(session) : session.getFinalNetWorth();
        long timeRemainingSeconds = Math.max(0, Duration.between(Instant.now(), session.getEndsAt()).getSeconds());

        Instant advisorNextTipAt = session.isAdvisorHired() && session.getAdvisorLastTipAt() != null
                ? session.getAdvisorLastTipAt().plusSeconds(ADVISOR_TIP_INTERVAL_SECONDS)
                : null;

        return new GameSessionResponse(
                session.getSessionId(), session.getClientId(), session.getDifficulty(), session.getStatus(),
                session.getCash(), currentSavingsValue(session), netWorth, session.getDifficulty().getGoalAmount(), timeRemainingSeconds,
                computeCurrentStreak(session.getSessionId()), session.getStartedAt(), session.getEndsAt(),
                session.getSpeedBoostAvailableAt(), marketService.currentSpeedMultiplier(session.getDifficulty()),
                session.isAdvisorHired(), session.getAdvisorTipSymbol(), session.getAdvisorTipSide(), advisorNextTipAt,
                session.getTotalDividendsPaid() != null ? session.getTotalDividendsPaid() : BigDecimal.ZERO,
                session.getTotalTaxPaid() != null ? session.getTotalTaxPaid() : BigDecimal.ZERO,
                positions, loans);
    }

    private GameTradeResponse toTradeResponse(GameTrade t) {
        return new GameTradeResponse(t.getTradeId(), t.getSymbol(), t.getSide(), t.getQuantity(), t.getPrice(), t.getFee(), t.getRealizedPnl(), t.getCreatedAt());
    }

    private void requireActive(GameSession session) {
        if (session.getStatus() != GameStatus.IN_PROGRESS) {
            throw new GameSessionNotActiveException(session.getSessionId());
        }
    }

    private GameSession requireSession(UUID sessionId) {
        return sessionRepository.findById(sessionId).orElseThrow(() -> new GameSessionNotFoundException(sessionId));
    }
}
