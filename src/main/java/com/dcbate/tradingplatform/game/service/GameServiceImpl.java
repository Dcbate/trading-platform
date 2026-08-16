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
import com.dcbate.tradingplatform.exception.GameInsufficientFundsException;
import com.dcbate.tradingplatform.exception.GameInsufficientPositionException;
import com.dcbate.tradingplatform.exception.GameLoanNotFoundException;
import com.dcbate.tradingplatform.exception.GameSessionNotActiveException;
import com.dcbate.tradingplatform.exception.GameSessionNotFoundException;
import com.dcbate.tradingplatform.exception.GameSessionStillInProgressException;
import com.dcbate.tradingplatform.game.api.dto.GameDebriefResponse;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRepayRequest;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRequest;
import com.dcbate.tradingplatform.game.api.dto.GameLoanResponse;
import com.dcbate.tradingplatform.game.api.dto.GamePositionResponse;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
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
    public GameSessionResponse takeLoan(UUID sessionId, GameLoanRequest request, CallerPrincipal caller) {
        GameSession session = requireSession(sessionId);
        caller.requireOwner(session.getClientId());
        evaluate(session);
        requireActive(session);

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
        BigDecimal fee = notional.multiply(session.getDifficulty().getFeeRate()).setScale(2, RoundingMode.HALF_UP);

        GameTrade trade = request.side() == OrderSide.BUY
                ? executeBuy(session, symbol, quantity, price, notional, fee)
                : executeSell(session, symbol, quantity, price, notional, fee);

        sessionRepository.save(session);
        tradeRepository.save(trade);
        log.info("Game trade: sessionId={}, symbol={}, side={}, quantity={}, price={}, fee={}, realizedPnl={}",
                sessionId, symbol, request.side(), quantity, price, fee, trade.getRealizedPnl());

        evaluate(session);
        return toResponse(session);
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

        BigDecimal proceeds = notional.subtract(fee);
        session.setCash(session.getCash().add(proceeds));

        BigDecimal realizedPnl = price.subtract(position.getAvgCost()).multiply(quantity).subtract(fee);
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

        GameDebriefResult result = gameCoach.debrief(new GameDebriefContext(narrative, fallbackSummary));
        log.info("Game debrief generated: sessionId={}, aiGenerated={}", sessionId, result.aiGenerated());
        return new GameDebriefResponse(result.summary(), result.aiGenerated(), symbolPerformance);
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
                        BigDecimal currentPrice = marketService.currentPrice(session.getDifficulty(), symbol).orElse(position.getAvgCost());
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
                .map(p -> marketService.currentPrice(session.getDifficulty(), p.getSymbol()).orElse(p.getAvgCost()).multiply(p.getQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loansOwed = loanRepository.findBySessionId(session.getSessionId()).stream()
                .map(this::totalOwed)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return session.getCash().add(positionsValue).subtract(loansOwed);
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
     * Rate applied per elapsed <b>minute</b>, not annualized — the real {@code Loan}'s interest
     * is a true annual rate accrued daily (see {@code LoanServiceImplTest}), which is the right
     * model for a loan that might sit outstanding for months. A Game Mode session lasts at most
     * 30 minutes; annualizing the same rate would make interest accrue in fractions of a penny
     * for the entire game (confirmed by playing it — a £5,000 loan at 20% APR held for the full
     * 30 minutes accrued about 6p), which defeats the entire point of modeling interest as a
     * cost. Treating the stated rate as "this fraction of principal, per minute held" keeps loans
     * a real, felt tradeoff within a session's actual timescale.
     */
    private BigDecimal pendingInterest(GameLoan loan) {
        double minutesElapsed = Duration.between(loan.getLastAccrualAt(), Instant.now()).toMillis() / 60_000.0;
        if (minutesElapsed <= 0) {
            return BigDecimal.ZERO;
        }
        return loan.getOutstandingPrincipal()
                .multiply(loan.getRateAnnualPercent())
                .multiply(BigDecimal.valueOf(minutesElapsed))
                .divide(BigDecimal.valueOf(100), 8, RoundingMode.HALF_UP);
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
                    BigDecimal marketValue = currentPrice.multiply(p.getQuantity());
                    BigDecimal unrealizedPnl = currentPrice.subtract(p.getAvgCost()).multiply(p.getQuantity());
                    return new GamePositionResponse(p.getSymbol(), p.getQuantity(), p.getAvgCost(), currentPrice, marketValue, unrealizedPnl);
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

        return new GameSessionResponse(
                session.getSessionId(), session.getClientId(), session.getDifficulty(), session.getStatus(),
                session.getCash(), netWorth, session.getDifficulty().getGoalAmount(), timeRemainingSeconds,
                session.getStartedAt(), session.getEndsAt(), positions, loans);
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
