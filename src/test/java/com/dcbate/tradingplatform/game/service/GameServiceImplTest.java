package com.dcbate.tradingplatform.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.domain.GameLoan;
import com.dcbate.tradingplatform.domain.GamePosition;
import com.dcbate.tradingplatform.domain.GameSession;
import com.dcbate.tradingplatform.domain.GameStatus;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.exception.GameInsufficientFundsException;
import com.dcbate.tradingplatform.exception.GameInsufficientPositionException;
import com.dcbate.tradingplatform.exception.GameSessionNotActiveException;
import com.dcbate.tradingplatform.exception.GameSessionNotFoundException;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRequest;
import com.dcbate.tradingplatform.game.api.dto.GameSessionResponse;
import com.dcbate.tradingplatform.game.api.dto.GameStatsResponse;
import com.dcbate.tradingplatform.game.api.dto.GameTradeRequest;
import com.dcbate.tradingplatform.game.api.dto.GameTradeResponse;
import com.dcbate.tradingplatform.game.repository.GameLoanRepository;
import com.dcbate.tradingplatform.game.repository.GamePositionRepository;
import com.dcbate.tradingplatform.game.repository.GameSessionRepository;
import com.dcbate.tradingplatform.game.repository.GameTradeRepository;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GameServiceImplTest {

    @Mock
    private GameSessionRepository sessionRepository;

    @Mock
    private GamePositionRepository positionRepository;

    @Mock
    private GameLoanRepository loanRepository;

    @Mock
    private GameTradeRepository tradeRepository;

    @Mock
    private GameMarketService marketService;

    private GameServiceImpl gameService;

    private final CallerPrincipal owner = new CallerPrincipal("client-1", false);
    private final CallerPrincipal otherClient = new CallerPrincipal("client-2", false);

    @BeforeEach
    void setUp() {
        gameService = new GameServiceImpl(sessionRepository, positionRepository, loanRepository, tradeRepository, marketService);
    }

    private GameSession session(GameDifficulty difficulty, BigDecimal cash, Instant endsAt) {
        return GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-1").difficulty(difficulty)
                .cash(cash).status(GameStatus.IN_PROGRESS).startedAt(Instant.now().minusSeconds(60)).endsAt(endsAt)
                .build();
    }

    @Test
    void startSessionCreatesNewSessionWithStartingCashAndGoal() {
        when(sessionRepository.findFirstByClientIdAndStatusOrderByStartedAtDesc("client-1", GameStatus.IN_PROGRESS))
                .thenReturn(Optional.empty());
        when(positionRepository.findBySessionId(any())).thenReturn(List.of());
        when(loanRepository.findBySessionId(any())).thenReturn(List.of());

        GameSessionResponse response = gameService.startSession("client-1", GameDifficulty.APPRENTICE, owner);

        assertThat(response.status()).isEqualTo(GameStatus.IN_PROGRESS);
        assertThat(response.cash()).isEqualByComparingTo(GameDifficulty.APPRENTICE.getStartingCash());
        assertThat(response.goalAmount()).isEqualByComparingTo(GameDifficulty.APPRENTICE.getGoalAmount());
        assertThat(response.timeRemainingSeconds()).isGreaterThan(0);
    }

    @Test
    void startSessionResumesExistingInProgressSessionInsteadOfCreatingANewOne() {
        GameSession existing = session(GameDifficulty.TRADER, new BigDecimal("6000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findFirstByClientIdAndStatusOrderByStartedAtDesc("client-1", GameStatus.IN_PROGRESS))
                .thenReturn(Optional.of(existing));
        when(positionRepository.findBySessionId(existing.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(existing.getSessionId())).thenReturn(List.of());

        GameSessionResponse response = gameService.startSession("client-1", GameDifficulty.MAVERICK, owner);

        assertThat(response.sessionId()).isEqualTo(existing.getSessionId());
        assertThat(response.difficulty()).isEqualTo(GameDifficulty.TRADER);
    }

    @Test
    void startSessionDeniedForNonOwner() {
        assertThatThrownBy(() -> gameService.startSession("client-1", GameDifficulty.APPRENTICE, otherClient))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getSessionFlipsToWonWhenNetWorthReachesGoal() {
        GameSession active = session(GameDifficulty.APPRENTICE, GameDifficulty.APPRENTICE.getGoalAmount(), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        GameSessionResponse response = gameService.getSession(active.getSessionId(), owner);

        assertThat(response.status()).isEqualTo(GameStatus.WON);
        assertThat(active.getFinalNetWorth()).isEqualByComparingTo(GameDifficulty.APPRENTICE.getGoalAmount());
    }

    @Test
    void getSessionFlipsToLostTimeWhenDeadlineHasPassed() {
        GameSession active = session(GameDifficulty.APPRENTICE, new BigDecimal("500"), Instant.now().minusSeconds(5));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        GameSessionResponse response = gameService.getSession(active.getSessionId(), owner);

        assertThat(response.status()).isEqualTo(GameStatus.LOST_TIME);
        assertThat(response.timeRemainingSeconds()).isZero();
    }

    @Test
    void getSessionFlipsToLostBankruptWhenNetWorthGoesNegative() {
        GameSession active = session(GameDifficulty.APPRENTICE, new BigDecimal("100"), Instant.now().plusSeconds(600));
        GameLoan hugeLoan = GameLoan.builder()
                .gameLoanId(UUID.randomUUID()).sessionId(active.getSessionId())
                .principal(new BigDecimal("10000")).rateAnnualPercent(new BigDecimal("5.00")).originatedAt(Instant.now())
                .build();
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(hugeLoan));

        GameSessionResponse response = gameService.getSession(active.getSessionId(), owner);

        assertThat(response.status()).isEqualTo(GameStatus.LOST_BANKRUPT);
    }

    @Test
    void getSessionDeniedForNonOwner() {
        GameSession active = session(GameDifficulty.APPRENTICE, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> gameService.getSession(active.getSessionId(), otherClient)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void getSessionThrowsWhenMissing() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gameService.getSession(sessionId, owner)).isInstanceOf(GameSessionNotFoundException.class);
    }

    @Test
    void takeLoanIncreasesCashAndPersistsLoanAtDifficultyRate() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        GameSessionResponse response = gameService.takeLoan(active.getSessionId(), new GameLoanRequest(new BigDecimal("2000")), owner);

        assertThat(response.cash()).isEqualByComparingTo("7000");
    }

    @Test
    void takeLoanRejectedOnAnEndedSession() {
        GameSession ended = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().minusSeconds(5));
        when(sessionRepository.findById(ended.getSessionId())).thenReturn(Optional.of(ended));
        when(positionRepository.findBySessionId(ended.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(ended.getSessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> gameService.takeLoan(ended.getSessionId(), new GameLoanRequest(new BigDecimal("1000")), owner))
                .isInstanceOf(GameSessionNotActiveException.class);
    }

    @Test
    void buyTradeDebitsCashByCostPlusFeeAndOpensAPosition() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.empty());
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        GameSessionResponse response = gameService.placeTrade(
                active.getSessionId(), new GameTradeRequest("AAPL", OrderSide.BUY, new BigDecimal("10")), owner);

        // notional 1000.00, fee = 1000 * 0.001 (Trader feeRate) = 1.00 -> cash 5000 - 1001.00 = 3999.00
        assertThat(response.cash()).isEqualByComparingTo("3999.00");
    }

    @Test
    void buyTradeThrowsWhenCashInsufficient() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("50"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("100.00")));

        assertThatThrownBy(() -> gameService.placeTrade(
                active.getSessionId(), new GameTradeRequest("AAPL", OrderSide.BUY, new BigDecimal("10")), owner))
                .isInstanceOf(GameInsufficientFundsException.class);
    }

    @Test
    void buyTradeThrowsOnUnknownSymbol() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(marketService.currentPrice(GameDifficulty.TRADER, "ZZZZ")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gameService.placeTrade(
                active.getSessionId(), new GameTradeRequest("ZZZZ", OrderSide.BUY, new BigDecimal("1")), owner))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void sellTradeRealizesPnlAndCreditsCash() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        GamePosition existingPosition = GamePosition.builder()
                .positionId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("AAPL")
                .quantity(new BigDecimal("10")).avgCost(new BigDecimal("100.00"))
                .build();
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("120.00")));
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.of(existingPosition));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        GameSessionResponse response = gameService.placeTrade(
                active.getSessionId(), new GameTradeRequest("AAPL", OrderSide.SELL, new BigDecimal("10")), owner);

        // notional 1200.00, fee 1.20, proceeds 1198.80 -> cash 1000 + 1198.80 = 2198.80
        assertThat(response.cash()).isEqualByComparingTo("2198.80");
    }

    @Test
    void sellTradeThrowsWhenPositionInsufficient() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("120.00")));
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> gameService.placeTrade(
                active.getSessionId(), new GameTradeRequest("AAPL", OrderSide.SELL, new BigDecimal("10")), owner))
                .isInstanceOf(GameInsufficientPositionException.class);
    }

    @Test
    void listTradesReturnsMostRecentFirst() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(tradeRepository.findBySessionIdOrderByCreatedAtDesc(active.getSessionId())).thenReturn(List.of());

        List<GameTradeResponse> trades = gameService.listTrades(active.getSessionId(), owner);

        assertThat(trades).isEmpty();
    }

    @Test
    void interestOwedGrowsWithElapsedTime() {
        GameLoan loan = GameLoan.builder()
                .gameLoanId(UUID.randomUUID()).sessionId(UUID.randomUUID())
                .principal(new BigDecimal("10000")).rateAnnualPercent(new BigDecimal("20.00"))
                .originatedAt(Instant.now().minus(Duration.ofDays(365)))
                .build();

        BigDecimal owed = gameService.interestOwed(loan);

        // ~1 year at 20% APR on 10,000 => ~2,000
        assertThat(owed).isCloseTo(new BigDecimal("2000"), org.assertj.core.data.Percentage.withPercentage(1));
    }

    @Test
    void getStatsComputesWinRateAndBestTrade() {
        GameSession won = GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-1").difficulty(GameDifficulty.APPRENTICE)
                .status(GameStatus.WON).finalNetWorth(new BigDecimal("11000"))
                .startedAt(Instant.now()).endsAt(Instant.now()).build();
        GameSession lost = GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-1").difficulty(GameDifficulty.APPRENTICE)
                .status(GameStatus.LOST_TIME).finalNetWorth(new BigDecimal("4000"))
                .startedAt(Instant.now()).endsAt(Instant.now()).build();
        when(sessionRepository.findByClientIdAndStatusNot("client-1", GameStatus.IN_PROGRESS)).thenReturn(List.of(won, lost));
        when(tradeRepository.findBestRealizedTrades(any())).thenReturn(List.of());

        GameStatsResponse stats = gameService.getStats("client-1", owner);

        assertThat(stats.totalGames()).isEqualTo(2);
        assertThat(stats.wins()).isEqualTo(1);
        assertThat(stats.winRatePercent()).isEqualTo(50.0);
        assertThat(stats.perDifficulty()).hasSize(1);
        assertThat(stats.perDifficulty().get(0).bestNetWorth()).isEqualByComparingTo("11000");
    }

    @Test
    void getStatsDeniedForNonOwner() {
        assertThatThrownBy(() -> gameService.getStats("client-1", otherClient)).isInstanceOf(AccessDeniedException.class);
    }
}
