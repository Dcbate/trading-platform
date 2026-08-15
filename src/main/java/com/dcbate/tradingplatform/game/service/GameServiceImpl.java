package com.dcbate.tradingplatform.game.service;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.domain.GameLoan;
import com.dcbate.tradingplatform.domain.GamePosition;
import com.dcbate.tradingplatform.domain.GameSession;
import com.dcbate.tradingplatform.domain.GameStatus;
import com.dcbate.tradingplatform.domain.GameTrade;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.exception.GameInsufficientFundsException;
import com.dcbate.tradingplatform.exception.GameInsufficientPositionException;
import com.dcbate.tradingplatform.exception.GameSessionNotActiveException;
import com.dcbate.tradingplatform.exception.GameSessionNotFoundException;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRequest;
import com.dcbate.tradingplatform.game.api.dto.GameLoanResponse;
import com.dcbate.tradingplatform.game.api.dto.GamePositionResponse;
import com.dcbate.tradingplatform.game.api.dto.GameSessionResponse;
import com.dcbate.tradingplatform.game.api.dto.GameStatsResponse;
import com.dcbate.tradingplatform.game.api.dto.GameStatsResponse.GameDifficultyStat;
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

    private static final long SECONDS_PER_YEAR = 365L * 24 * 3600;

    private final GameSessionRepository sessionRepository;
    private final GamePositionRepository positionRepository;
    private final GameLoanRepository loanRepository;
    private final GameTradeRepository tradeRepository;
    private final GameMarketService marketService;

    @Override
    @Transactional
    public GameSessionResponse startSession(GameDifficulty difficulty, CallerPrincipal caller) {
        var existing = sessionRepository.findFirstByClientIdAndStatusOrderByStartedAtDesc(caller.clientId(), GameStatus.IN_PROGRESS);
        if (existing.isPresent()) {
            GameSession session = existing.get();
            evaluate(session);
            if (session.getStatus() == GameStatus.IN_PROGRESS) {
                return toResponse(session);
            }
        }

        Instant now = Instant.now();
        GameSession session = GameSession.builder()
                .sessionId(UUID.randomUUID())
                .clientId(caller.clientId())
                .difficulty(difficulty)
                .cash(difficulty.getStartingCash())
                .status(GameStatus.IN_PROGRESS)
                .startedAt(now)
                .endsAt(now.plus(Duration.ofMinutes(difficulty.getDurationMinutes())))
                .build();
        sessionRepository.save(session);
        log.info("Game session started: sessionId={}, clientId={}, difficulty={}", session.getSessionId(), caller.clientId(), difficulty);
        return toResponse(session);
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

        loanRepository.save(GameLoan.builder()
                .gameLoanId(UUID.randomUUID())
                .sessionId(sessionId)
                .principal(request.amount())
                .rateAnnualPercent(session.getDifficulty().getLoanRateAnnualPercent())
                .originatedAt(Instant.now())
                .build());

        log.info("Game loan taken: sessionId={}, amount={}, rate={}", sessionId, request.amount(), session.getDifficulty().getLoanRateAnnualPercent());
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
        return loan.getPrincipal().add(interestOwed(loan));
    }

    /** Simple (non-compounding) interest, computed from elapsed wall-clock time rather than accrued/persisted incrementally — a session never runs longer than 30 minutes, so there's nothing a scheduled job would need to catch up on between reads. */
    BigDecimal interestOwed(GameLoan loan) {
        long secondsElapsed = Duration.between(loan.getOriginatedAt(), Instant.now()).getSeconds();
        if (secondsElapsed <= 0) {
            return BigDecimal.ZERO;
        }
        return loan.getPrincipal()
                .multiply(loan.getRateAnnualPercent())
                .multiply(BigDecimal.valueOf(secondsElapsed))
                .divide(BigDecimal.valueOf(100L * SECONDS_PER_YEAR), 8, RoundingMode.HALF_UP);
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

        List<GameLoanResponse> loans = loanRepository.findBySessionId(session.getSessionId()).stream()
                .map(l -> new GameLoanResponse(l.getGameLoanId(), l.getPrincipal(), interestOwed(l), l.getRateAnnualPercent(), l.getOriginatedAt()))
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
