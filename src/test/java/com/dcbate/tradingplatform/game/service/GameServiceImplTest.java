package com.dcbate.tradingplatform.game.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.ai.GameCoach;
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
import com.dcbate.tradingplatform.exception.GameInsufficientSavingsException;
import com.dcbate.tradingplatform.exception.GameLoanDeclinedException;
import com.dcbate.tradingplatform.exception.GameSessionNotActiveException;
import com.dcbate.tradingplatform.exception.GameSessionNotFoundException;
import com.dcbate.tradingplatform.exception.GameAdvisorAlreadyHiredException;
import com.dcbate.tradingplatform.exception.GamePositionAlreadyInsuredException;
import com.dcbate.tradingplatform.exception.GameSessionStillInProgressException;
import com.dcbate.tradingplatform.exception.GameSpeedBoostOnCooldownException;
import com.dcbate.tradingplatform.game.api.dto.GameDebriefResponse;
import com.dcbate.tradingplatform.game.api.dto.GameInsuranceRequest;
import com.dcbate.tradingplatform.game.api.dto.GameLeaderboardSortBy;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRequest;
import com.dcbate.tradingplatform.game.api.dto.GameSavingsRequest;
import com.dcbate.tradingplatform.game.api.dto.GameSessionResponse;
import com.dcbate.tradingplatform.game.api.dto.GameStatsResponse;
import com.dcbate.tradingplatform.game.api.dto.GameSymbolPerformanceResponse;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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

    @Mock
    private GameCoach gameCoach;

    private GameServiceImpl gameService;

    private final CallerPrincipal owner = new CallerPrincipal("client-1", false);
    private final CallerPrincipal otherClient = new CallerPrincipal("client-2", false);

    @BeforeEach
    void setUp() {
        gameService = new GameServiceImpl(sessionRepository, positionRepository, loanRepository, tradeRepository, marketService, gameCoach);
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

        GameSessionStartResult result = gameService.startSession("client-1", GameDifficulty.APPRENTICE, owner);
        GameSessionResponse response = result.session();

        assertThat(result.created()).isTrue();
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

        GameSessionStartResult result = gameService.startSession("client-1", GameDifficulty.MAVERICK, owner);
        GameSessionResponse response = result.session();

        assertThat(result.created()).isFalse();
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
                .principal(new BigDecimal("10000")).outstandingPrincipal(new BigDecimal("10000")).accruedInterest(BigDecimal.ZERO)
                .rateAnnualPercent(new BigDecimal("5.00")).originatedAt(Instant.now()).lastAccrualAt(Instant.now())
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
    void activateSpeedBoostSucceedsWhenOffCooldownAndSetsTheNextAvailableTime() {
        // No speedBoostAvailableAt set on this session (the default via the `session()` helper) —
        // treated as never-boosted, i.e. immediately available, same as a freshly started session.
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        gameService.activateSpeedBoost(active.getSessionId(), owner);

        verify(marketService).activateSpeedBoost(GameDifficulty.TRADER);
        assertThat(active.getSpeedBoostAvailableAt()).isAfter(Instant.now().plusSeconds(80));
    }

    @Test
    void activateSpeedBoostDeclinedWhileOnCooldown() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        active.setSpeedBoostAvailableAt(Instant.now().plusSeconds(60));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> gameService.activateSpeedBoost(active.getSessionId(), owner))
                .isInstanceOf(GameSpeedBoostOnCooldownException.class);
    }

    @Test
    void hireAdvisorDeductsFeeScaledToDifficultyStartingCash() {
        // TRADER starting cash is 5,000; the 2% hire fee is therefore 100.
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        GameSessionResponse response = gameService.hireAdvisor(active.getSessionId(), owner);

        assertThat(response.cash()).isEqualByComparingTo("4900");
        assertThat(response.advisorHired()).isTrue();
    }

    @Test
    void hireAdvisorRejectedWhenAlreadyHired() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        active.setAdvisorHired(true);
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> gameService.hireAdvisor(active.getSessionId(), owner))
                .isInstanceOf(GameAdvisorAlreadyHiredException.class);
    }

    @Test
    void hireAdvisorRejectedWhenCashInsufficient() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("50"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> gameService.hireAdvisor(active.getSessionId(), owner))
                .isInstanceOf(GameInsufficientFundsException.class);
    }

    @Test
    void advisorTipDoesNotRefreshBeforeTheIntervalElapses() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        active.setAdvisorHired(true);
        active.setAdvisorTipSymbol("AAPL");
        active.setAdvisorLastTipAt(Instant.now());

        gameService.settleAdvisorTip(active);

        assertThat(active.getAdvisorTipSymbol()).isEqualTo("AAPL");
        verify(marketService, never()).currentPrices(any());
    }

    @Test
    void advisorTipAccuracyLandsNearTheConfiguredRateOverManyRolls() {
        // A single-symbol market with an always-up regime isolates the accuracy roll from symbol
        // selection: the tip should agree with "up" (i.e. BUY) roughly 62% of the time.
        when(marketService.currentPrices(GameDifficulty.TRADER)).thenReturn(Map.of("AAPL", new BigDecimal("100")));
        when(marketService.currentTrendUp(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(true));

        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        active.setAdvisorHired(true);

        int buyTips = 0;
        int rolls = 2000;
        for (int i = 0; i < rolls; i++) {
            active.setAdvisorLastTipAt(null);
            gameService.settleAdvisorTip(active);
            if (active.getAdvisorTipSide() == OrderSide.BUY) {
                buyTips++;
            }
        }

        double observedAccuracy = buyTips / (double) rolls;
        assertThat(observedAccuracy).isCloseTo(0.62, org.assertj.core.data.Percentage.withPercentage(15));
    }

    @Test
    void settleDividendsAccruesOnHeldStockPositionsIntoCash() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        GamePosition position = GamePosition.builder()
                .positionId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("AAPL")
                .quantity(new BigDecimal("10")).avgCost(new BigDecimal("100"))
                .dividendLastAccrualAt(Instant.now().minusSeconds(60))
                .build();
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(position));
        when(marketService.isStockSymbol("AAPL")).thenReturn(true);
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("100")));

        gameService.settleDividends(active);

        // 1 minute (= 6 compressed days) at 2% APR on a 1,000 market value => 1000*2*6/(100*365) ≈ 0.33
        assertThat(active.getCash()).isCloseTo(new BigDecimal("1000.33"), within(new BigDecimal("0.01")));
        assertThat(active.getTotalDividendsPaid()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void settleDividendsSkipsFxPositions() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        GamePosition position = GamePosition.builder()
                .positionId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("EUR/USD")
                .quantity(new BigDecimal("1000")).avgCost(new BigDecimal("1.08"))
                .dividendLastAccrualAt(Instant.now().minusSeconds(60))
                .build();
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(position));
        when(marketService.isStockSymbol("EUR/USD")).thenReturn(false);

        gameService.settleDividends(active);

        assertThat(active.getCash()).isEqualByComparingTo("1000");
        verify(positionRepository, never()).save(any());
    }

    @Test
    void settleDividendsDoesNothingForAFreshlyOpenedPosition() {
        // dividendLastAccrualAt = now, so no compressed days have elapsed yet — a position bought
        // this instant shouldn't pay out for time before it was even held.
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        GamePosition position = GamePosition.builder()
                .positionId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("AAPL")
                .quantity(new BigDecimal("10")).avgCost(new BigDecimal("100"))
                .dividendLastAccrualAt(Instant.now())
                .build();
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(position));
        when(marketService.isStockSymbol("AAPL")).thenReturn(true);

        gameService.settleDividends(active);

        assertThat(active.getCash()).isEqualByComparingTo("1000");
    }

    @Test
    void buyingANewStockPositionSetsTheDividendAccrualClockToNow() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.empty());
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        gameService.placeTrade(active.getSessionId(), new GameTradeRequest("AAPL", OrderSide.BUY, new BigDecimal("10")), owner);

        ArgumentCaptor<GamePosition> captor = ArgumentCaptor.forClass(GamePosition.class);
        verify(positionRepository).save(captor.capture());
        assertThat(captor.getValue().getDividendLastAccrualAt()).isAfter(Instant.now().minusSeconds(5));
    }

    @Test
    void totalDividendsPaidAccumulatesAcrossMultipleSettlements() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        active.setTotalDividendsPaid(new BigDecimal("2.00"));
        GamePosition position = GamePosition.builder()
                .positionId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("AAPL")
                .quantity(new BigDecimal("10")).avgCost(new BigDecimal("100"))
                .dividendLastAccrualAt(Instant.now().minusSeconds(60))
                .build();
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(position));
        when(marketService.isStockSymbol("AAPL")).thenReturn(true);
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("100")));

        gameService.settleDividends(active);

        assertThat(active.getTotalDividendsPaid()).isGreaterThan(new BigDecimal("2.00"));
    }

    @Test
    void purchaseInsuranceDeductsPremiumAndSetsTheFloor() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        GamePosition position = GamePosition.builder()
                .positionId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("AAPL")
                .quantity(new BigDecimal("10")).avgCost(new BigDecimal("100"))
                .build();
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(position));
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.of(position));
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("100")));

        GameSessionResponse response = gameService.purchaseInsurance(active.getSessionId(), new GameInsuranceRequest("AAPL"), owner);

        // premium = 1000 (position value) * 3% = 30
        assertThat(response.cash()).isEqualByComparingTo("4970");
        assertThat(position.isInsured()).isTrue();
        // floor = avgCost 100 * 85% = 85
        assertThat(position.getInsuranceFloorPrice()).isEqualByComparingTo("85.00");
    }

    @Test
    void purchaseInsuranceRejectedWhenAlreadyInsured() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        GamePosition position = GamePosition.builder()
                .positionId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("AAPL")
                .quantity(new BigDecimal("10")).avgCost(new BigDecimal("100")).insured(true).insuranceFloorPrice(new BigDecimal("85"))
                .build();
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(position));
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.of(position));
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> gameService.purchaseInsurance(active.getSessionId(), new GameInsuranceRequest("AAPL"), owner))
                .isInstanceOf(GamePositionAlreadyInsuredException.class);
    }

    @Test
    void purchaseInsuranceRejectedWhenNoPositionHeld() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.empty());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> gameService.purchaseInsurance(active.getSessionId(), new GameInsuranceRequest("AAPL"), owner))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void netWorthNeverFallsBelowTheInsuredFloorEvenIfThePriceCrashes() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        GamePosition insured = GamePosition.builder()
                .positionId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("AAPL")
                .quantity(new BigDecimal("10")).avgCost(new BigDecimal("100"))
                .insured(true).insuranceFloorPrice(new BigDecimal("85"))
                .build();
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(insured));
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        // Real price has crashed to 20 — well below the 85 floor.
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("20")));

        BigDecimal netWorth = gameService.computeNetWorth(active);

        // 1000 cash + 10 shares marked at the 85 floor (not the real 20) = 1850
        assertThat(netWorth).isEqualByComparingTo("1850");
    }

    @Test
    void sellingAnInsuredPositionBelowTheFloorSettlesAtTheFloor() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        GamePosition insured = GamePosition.builder()
                .positionId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("AAPL")
                .quantity(new BigDecimal("10")).avgCost(new BigDecimal("100"))
                .insured(true).insuranceFloorPrice(new BigDecimal("85"))
                .build();
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.of(insured));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        // Real market price is 20, well below the 85 floor.
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("20")));

        GameSessionResponse response = gameService.placeTrade(
                active.getSessionId(), new GameTradeRequest("AAPL", OrderSide.SELL, new BigDecimal("10")), owner);

        // Proceeds at the 85 floor (fee = 20*10*0.001 = 0.20): 1000 + (85*10 - 0.20) = 1849.80
        assertThat(response.cash()).isEqualByComparingTo("1849.80");
    }

    @Test
    void uninsuredPositionStillMarksAtTheRealPrice() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        GamePosition uninsured = GamePosition.builder()
                .positionId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("AAPL")
                .quantity(new BigDecimal("10")).avgCost(new BigDecimal("100"))
                .build();
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(uninsured));
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("20")));

        BigDecimal netWorth = gameService.computeNetWorth(active);

        // 1000 cash + 10 shares marked at the real crashed price of 20 = 1200
        assertThat(netWorth).isEqualByComparingTo("1200");
    }

    @Test
    void settleTaxDeductsFifteenPercentOfNewlyRealizedProfit() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        active.setTaxLastSettledAt(Instant.now().minusSeconds(120));
        when(tradeRepository.sumRealizedPnl(active.getSessionId())).thenReturn(new BigDecimal("200"));

        gameService.settleTax(active);

        // 200 newly realized profit * 15% = 30
        assertThat(active.getCash()).isEqualByComparingTo("970");
        assertThat(active.getTotalTaxPaid()).isEqualByComparingTo("30");
        assertThat(active.getTotalRealizedPnlTaxed()).isEqualByComparingTo("200");
    }

    @Test
    void settleTaxDoesNothingBeforeTheIntervalElapses() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        active.setTaxLastSettledAt(Instant.now());

        gameService.settleTax(active);

        assertThat(active.getCash()).isEqualByComparingTo("1000");
        verify(tradeRepository, never()).sumRealizedPnl(any());
    }

    @Test
    void aLaterLossDoesNotRefundTaxOrLowerTheHighWaterMark() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        active.setTaxLastSettledAt(Instant.now().minusSeconds(120));
        active.setTotalRealizedPnlTaxed(new BigDecimal("200"));
        active.setTotalTaxPaid(new BigDecimal("30"));
        // Realized P&L has since dropped to 150 (a loss closed after the earlier 200 profit) —
        // still below the 200 already taxed, so nothing should be refunded or reduced.
        when(tradeRepository.sumRealizedPnl(active.getSessionId())).thenReturn(new BigDecimal("150"));

        gameService.settleTax(active);

        assertThat(active.getCash()).isEqualByComparingTo("1000");
        assertThat(active.getTotalTaxPaid()).isEqualByComparingTo("30");
        assertThat(active.getTotalRealizedPnlTaxed()).isEqualByComparingTo("200");
    }

    @Test
    void repeatedProfitAboveTheOldHighWaterMarkTaxesOnlyTheNewDelta() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        active.setTaxLastSettledAt(Instant.now().minusSeconds(120));
        active.setTotalRealizedPnlTaxed(new BigDecimal("200"));
        active.setTotalTaxPaid(new BigDecimal("30"));
        // Total realized P&L has grown from 200 to 350 — only the new 150 delta should be taxed.
        when(tradeRepository.sumRealizedPnl(active.getSessionId())).thenReturn(new BigDecimal("350"));

        gameService.settleTax(active);

        // 150 new profit * 15% = 22.50, on top of the 30 already paid = 52.50
        assertThat(active.getCash()).isEqualByComparingTo("977.50");
        assertThat(active.getTotalTaxPaid()).isEqualByComparingTo("52.50");
        assertThat(active.getTotalRealizedPnlTaxed()).isEqualByComparingTo("350");
    }

    @Test
    void takeLoanDeclinedWhenRequestedExposureExceedsNetWorthMultiple() {
        // Net worth here is just the 100 cash (no positions, no existing loans). The credit limit
        // is 1.5x that (150), so a 10,000 request should be declined rather than silently granted.
        GameSession active = session(GameDifficulty.APPRENTICE, new BigDecimal("100"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> gameService.takeLoan(active.getSessionId(), new GameLoanRequest(new BigDecimal("10000")), owner))
                .isInstanceOf(GameLoanDeclinedException.class);
    }

    @Test
    void takeLoanDeclinedWhenExistingLoansAlreadyConsumeTheCreditLimit() {
        // Net worth is 5,000 cash minus the 4,000 already owed on the existing loan = 1,000, so the
        // credit limit (1.5x = 1,500) is already exceeded by the 4,000 outstanding alone — no
        // further borrowing should be approved regardless of how small the new request is.
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        GameLoan existingLoan = GameLoan.builder()
                .gameLoanId(UUID.randomUUID()).sessionId(active.getSessionId())
                .principal(new BigDecimal("4000")).outstandingPrincipal(new BigDecimal("4000")).accruedInterest(BigDecimal.ZERO)
                .rateAnnualPercent(new BigDecimal("8.00")).originatedAt(Instant.now()).lastAccrualAt(Instant.now())
                .build();
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(existingLoan));

        assertThatThrownBy(() -> gameService.takeLoan(active.getSessionId(), new GameLoanRequest(new BigDecimal("100")), owner))
                .isInstanceOf(GameLoanDeclinedException.class);
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
    void depositToSavingsMovesCashIntoSavingsBalance() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        GameSessionResponse response = gameService.depositToSavings(active.getSessionId(), new GameSavingsRequest(new BigDecimal("2000")), owner);

        assertThat(response.cash()).isEqualByComparingTo("3000");
        // Not exactly 2000: the response always shows the truly live value (persisted balance plus
        // whatever's accrued since the settle a moment ago), so even the handful of microseconds
        // between settling and serializing this response ticks in a tiny amount of interest.
        assertThat(response.savingsBalance()).isCloseTo(new BigDecimal("2000"), within(new BigDecimal("0.01")));
    }

    @Test
    void depositToSavingsRejectedWhenCashInsufficient() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("500"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> gameService.depositToSavings(active.getSessionId(), new GameSavingsRequest(new BigDecimal("501")), owner))
                .isInstanceOf(GameInsufficientFundsException.class);
    }

    @Test
    void withdrawFromSavingsMovesSavingsBackToCash() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("3000"), Instant.now().plusSeconds(600));
        active.setSavingsBalance(new BigDecimal("2000"));
        active.setSavingsLastAccrualAt(Instant.now());
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        GameSessionResponse response = gameService.withdrawFromSavings(active.getSessionId(), new GameSavingsRequest(new BigDecimal("1000")), owner);

        assertThat(response.cash()).isEqualByComparingTo("4000");
        // Not exactly 1000: same live-ticking-value reasoning as depositToSavingsMovesCashIntoSavingsBalance above
        // — the response always shows the truly live balance, so even a few microseconds between settling and
        // serializing this response ticks in a tiny amount of interest.
        assertThat(response.savingsBalance()).isCloseTo(new BigDecimal("1000"), within(new BigDecimal("0.01")));
    }

    @Test
    void withdrawFromSavingsRejectedWhenBalanceInsufficient() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("3000"), Instant.now().plusSeconds(600));
        active.setSavingsBalance(new BigDecimal("200"));
        active.setSavingsLastAccrualAt(Instant.now());
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> gameService.withdrawFromSavings(active.getSessionId(), new GameSavingsRequest(new BigDecimal("201")), owner))
                .isInstanceOf(GameInsufficientSavingsException.class);
    }

    @Test
    void savingsBalanceAccruesInterestOverTime() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("3000"), Instant.now().plusSeconds(600));
        active.setSavingsBalance(new BigDecimal("10000"));
        active.setSavingsLastAccrualAt(Instant.now().minusSeconds(60));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        // 1 minute (= 6 compressed days) at 3% APR on 10,000 => 10000*3*6/(100*365) ≈ 4.93 — the
        // live value should already reflect this without a deposit/withdrawal settling it.
        BigDecimal netWorth = gameService.computeNetWorth(active);

        assertThat(netWorth).isGreaterThan(new BigDecimal("13000"));
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
    void buyTradeWaivesFeeWhenReactingToRecentMarketEvent() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.empty());
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(marketService.recentEvents(GameDifficulty.TRADER)).thenReturn(
                List.of(new GameMarketService.GameMarketEvent("AAPL", "AAPL crashes 20% — panic selling wipes out gains", false, 20, Instant.now())));

        GameSessionResponse response = gameService.placeTrade(
                active.getSessionId(), new GameTradeRequest("AAPL", OrderSide.BUY, new BigDecimal("10")), owner);

        // notional 1000.00, fee waived (reaction trade) -> cash 5000 - 1000.00 = 4000.00, not the usual 3999.00
        assertThat(response.cash()).isEqualByComparingTo("4000.00");
    }

    @Test
    void buyTradeChargesNormalFeeWhenMarketEventIsForADifferentSymbol() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.empty());
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(marketService.recentEvents(GameDifficulty.TRADER)).thenReturn(
                List.of(new GameMarketService.GameMarketEvent("MSFT", "MSFT rallies 15% — buyers pile in all at once", true, 15, Instant.now())));

        GameSessionResponse response = gameService.placeTrade(
                active.getSessionId(), new GameTradeRequest("AAPL", OrderSide.BUY, new BigDecimal("10")), owner);

        assertThat(response.cash()).isEqualByComparingTo("3999.00");
    }

    @Test
    void buyTradeChargesNormalFeeWhenMarketEventIsTooOld() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(marketService.currentPrice(GameDifficulty.TRADER, "AAPL")).thenReturn(Optional.of(new BigDecimal("100.00")));
        when(positionRepository.findBySessionIdAndSymbol(active.getSessionId(), "AAPL")).thenReturn(Optional.empty());
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(marketService.recentEvents(GameDifficulty.TRADER)).thenReturn(
                List.of(new GameMarketService.GameMarketEvent("AAPL", "AAPL crashes 20%", false, 20, Instant.now().minusSeconds(30))));

        GameSessionResponse response = gameService.placeTrade(
                active.getSessionId(), new GameTradeRequest("AAPL", OrderSide.BUY, new BigDecimal("10")), owner);

        assertThat(response.cash()).isEqualByComparingTo("3999.00");
    }

    @Test
    void currentStreakCountsConsecutiveProfitableClosedTradesFromMostRecent() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        // Most-recent-first: two wins, then a loss — streak should stop counting at the loss.
        List<GameTrade> trades = List.of(
                GameTrade.builder().tradeId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("AAPL")
                        .side(OrderSide.SELL).quantity(BigDecimal.ONE).price(new BigDecimal("110")).fee(BigDecimal.ZERO)
                        .realizedPnl(new BigDecimal("10")).createdAt(Instant.now()).build(),
                GameTrade.builder().tradeId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("MSFT")
                        .side(OrderSide.SELL).quantity(BigDecimal.ONE).price(new BigDecimal("210")).fee(BigDecimal.ZERO)
                        .realizedPnl(new BigDecimal("20")).createdAt(Instant.now().minusSeconds(10)).build(),
                GameTrade.builder().tradeId(UUID.randomUUID()).sessionId(active.getSessionId()).symbol("NVDA")
                        .side(OrderSide.SELL).quantity(BigDecimal.ONE).price(new BigDecimal("90")).fee(BigDecimal.ZERO)
                        .realizedPnl(new BigDecimal("-5")).createdAt(Instant.now().minusSeconds(20)).build());
        when(tradeRepository.findBySessionIdOrderByCreatedAtDesc(active.getSessionId())).thenReturn(trades);

        GameSessionResponse response = gameService.getSession(active.getSessionId(), owner);

        assertThat(response.currentStreak()).isEqualTo(2);
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
        // Each elapsed minute stands in for LOAN_INTEREST_DAYS_PER_MINUTE (6) compressed "days" in
        // the real day-based simple interest formula (principal * rate * days / (100 * 365)) —
        // see GameServiceImpl.pendingInterest.
        GameLoan loan = GameLoan.builder()
                .gameLoanId(UUID.randomUUID()).sessionId(UUID.randomUUID())
                .principal(new BigDecimal("10000")).outstandingPrincipal(new BigDecimal("10000")).accruedInterest(BigDecimal.ZERO)
                .rateAnnualPercent(new BigDecimal("20.00"))
                .originatedAt(Instant.now().minus(Duration.ofMinutes(1)))
                .lastAccrualAt(Instant.now().minus(Duration.ofMinutes(1)))
                .build();

        BigDecimal owed = gameService.interestOwed(loan);

        // 1 minute (= 6 compressed days) at 20% APR on 10,000 => 10000*20*6/(100*365) ≈ 32.88
        assertThat(owed).isCloseTo(new BigDecimal("32.88"), org.assertj.core.data.Percentage.withPercentage(2));
    }

    @Test
    void repayLoanAppliesToInterestFirstThenPrincipal() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        // Rate is zero so no further interest accrues between loan setup and the repay call below —
        // isolates the assertion to the interest-then-principal payment ordering itself.
        GameLoan loan = GameLoan.builder()
                .gameLoanId(UUID.randomUUID()).sessionId(active.getSessionId())
                .principal(new BigDecimal("2000")).outstandingPrincipal(new BigDecimal("2000"))
                .accruedInterest(new BigDecimal("300")).rateAnnualPercent(BigDecimal.ZERO)
                .originatedAt(Instant.now()).lastAccrualAt(Instant.now())
                .build();
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(loanRepository.findById(loan.getGameLoanId())).thenReturn(Optional.of(loan));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of(loan));

        GameSessionResponse response = gameService.repayLoan(
                active.getSessionId(), loan.getGameLoanId(), new com.dcbate.tradingplatform.game.api.dto.GameLoanRepayRequest(new BigDecimal("500")), owner);

        // 500 paid: 300 clears accrued interest, remaining 200 comes off outstanding principal.
        assertThat(loan.getAccruedInterest()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(loan.getOutstandingPrincipal()).isEqualByComparingTo("1800");
        assertThat(response.cash()).isEqualByComparingTo("4500");
    }

    @Test
    void repayLoanNeverTakesMoreThanWhatsOwed() {
        GameSession active = session(GameDifficulty.TRADER, new BigDecimal("5000"), Instant.now().plusSeconds(600));
        GameLoan loan = GameLoan.builder()
                .gameLoanId(UUID.randomUUID()).sessionId(active.getSessionId())
                .principal(new BigDecimal("100")).outstandingPrincipal(new BigDecimal("100"))
                .accruedInterest(BigDecimal.ZERO).rateAnnualPercent(BigDecimal.ZERO)
                .originatedAt(Instant.now()).lastAccrualAt(Instant.now())
                .build();
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(loanRepository.findById(loan.getGameLoanId())).thenReturn(Optional.of(loan));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        GameSessionResponse response = gameService.repayLoan(
                active.getSessionId(), loan.getGameLoanId(), new com.dcbate.tradingplatform.game.api.dto.GameLoanRepayRequest(new BigDecimal("10000")), owner);

        assertThat(loan.getOutstandingPrincipal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.cash()).isEqualByComparingTo("4900");
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

    @Test
    void getLeaderboardRanksByNetWorthAndFlagsCallersOwnRow() {
        GameSession first = GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-2").difficulty(GameDifficulty.APPRENTICE)
                .status(GameStatus.WON).finalNetWorth(new BigDecimal("15000"))
                .startedAt(Instant.now()).endsAt(Instant.now()).finishedAt(Instant.now()).build();
        GameSession second = GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-1").difficulty(GameDifficulty.APPRENTICE)
                .status(GameStatus.WON).finalNetWorth(new BigDecimal("11000"))
                .startedAt(Instant.now()).endsAt(Instant.now()).finishedAt(Instant.now()).build();
        when(sessionRepository.findTop10ByDifficultyAndFinalNetWorthIsNotNullOrderByFinalNetWorthDesc(GameDifficulty.APPRENTICE))
                .thenReturn(List.of(first, second));

        var leaderboard = gameService.getLeaderboard(GameDifficulty.APPRENTICE, GameLeaderboardSortBy.NET_WORTH, "client-1");

        assertThat(leaderboard.difficulty()).isEqualTo(GameDifficulty.APPRENTICE);
        assertThat(leaderboard.sortBy()).isEqualTo(GameLeaderboardSortBy.NET_WORTH);
        assertThat(leaderboard.entries()).hasSize(2);
        assertThat(leaderboard.entries().get(0).rank()).isEqualTo(1);
        assertThat(leaderboard.entries().get(0).netWorth()).isEqualByComparingTo("15000");
        assertThat(leaderboard.entries().get(0).mine()).isFalse();
        assertThat(leaderboard.entries().get(1).rank()).isEqualTo(2);
        assertThat(leaderboard.entries().get(1).mine()).isTrue();
    }

    @Test
    void getLeaderboardFastestWinRanksWonSessionsByDurationAscending() {
        Instant start = Instant.now().minusSeconds(1000);
        GameSession slowWin = GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-2").difficulty(GameDifficulty.APPRENTICE)
                .status(GameStatus.WON).finalNetWorth(new BigDecimal("10500"))
                .startedAt(start).endsAt(start.plusSeconds(900)).finishedAt(start.plusSeconds(800)).build();
        GameSession fastWin = GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-1").difficulty(GameDifficulty.APPRENTICE)
                .status(GameStatus.WON).finalNetWorth(new BigDecimal("10100"))
                .startedAt(start).endsAt(start.plusSeconds(900)).finishedAt(start.plusSeconds(200)).build();
        when(sessionRepository.findByDifficultyAndStatus(GameDifficulty.APPRENTICE, GameStatus.WON))
                .thenReturn(List.of(slowWin, fastWin));

        var leaderboard = gameService.getLeaderboard(GameDifficulty.APPRENTICE, GameLeaderboardSortBy.FASTEST_WIN, "client-1");

        assertThat(leaderboard.sortBy()).isEqualTo(GameLeaderboardSortBy.FASTEST_WIN);
        assertThat(leaderboard.entries()).hasSize(2);
        assertThat(leaderboard.entries().get(0).durationSeconds()).isEqualTo(200);
        assertThat(leaderboard.entries().get(0).mine()).isTrue();
        assertThat(leaderboard.entries().get(1).durationSeconds()).isEqualTo(800);
    }

    @Test
    void getLeaderboardNeverExposesAClientId() {
        // No CallerPrincipal/ownership check at all — this is a deliberately public endpoint, and
        // the response type itself (GameLeaderboardEntry) has no clientId field to leak.
        when(sessionRepository.findTop10ByDifficultyAndFinalNetWorthIsNotNullOrderByFinalNetWorthDesc(GameDifficulty.ROGUE))
                .thenReturn(List.of());

        var leaderboard = gameService.getLeaderboard(GameDifficulty.ROGUE, GameLeaderboardSortBy.NET_WORTH, null);

        assertThat(leaderboard.entries()).isEmpty();
    }

    @Test
    void getDebriefThrowsWhenSessionStillInProgress() {
        GameSession active = session(GameDifficulty.APPRENTICE, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));
        when(positionRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(active.getSessionId())).thenReturn(List.of());

        assertThatThrownBy(() -> gameService.getDebrief(active.getSessionId(), owner))
                .isInstanceOf(GameSessionStillInProgressException.class);
    }

    @Test
    void getDebriefAggregatesPerSymbolPnlAndDelegatesToTheCoach() {
        GameSession ended = GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-1").difficulty(GameDifficulty.APPRENTICE)
                .status(GameStatus.WON).cash(new BigDecimal("2000")).finalNetWorth(new BigDecimal("11000"))
                .startedAt(Instant.now().minusSeconds(900)).endsAt(Instant.now()).build();
        // Closed out AAPL for a realized profit; still holding MSFT with an unrealized gain.
        GameTrade buyAapl = GameTrade.builder().tradeId(UUID.randomUUID()).sessionId(ended.getSessionId()).symbol("AAPL")
                .side(OrderSide.BUY).quantity(new BigDecimal("5")).price(new BigDecimal("100")).fee(BigDecimal.ZERO)
                .realizedPnl(null).createdAt(Instant.now().minusSeconds(800)).build();
        GameTrade sellAapl = GameTrade.builder().tradeId(UUID.randomUUID()).sessionId(ended.getSessionId()).symbol("AAPL")
                .side(OrderSide.SELL).quantity(new BigDecimal("5")).price(new BigDecimal("150")).fee(BigDecimal.ZERO)
                .realizedPnl(new BigDecimal("250")).createdAt(Instant.now().minusSeconds(700)).build();
        GamePosition msft = GamePosition.builder().positionId(UUID.randomUUID()).sessionId(ended.getSessionId())
                .symbol("MSFT").quantity(new BigDecimal("10")).avgCost(new BigDecimal("200")).build();

        when(sessionRepository.findById(ended.getSessionId())).thenReturn(Optional.of(ended));
        when(tradeRepository.findBySessionIdOrderByCreatedAtDesc(ended.getSessionId())).thenReturn(List.of(sellAapl, buyAapl));
        when(positionRepository.findBySessionId(ended.getSessionId())).thenReturn(List.of(msft));
        when(loanRepository.findBySessionId(ended.getSessionId())).thenReturn(List.of());
        when(marketService.currentPrice(GameDifficulty.APPRENTICE, "MSFT")).thenReturn(Optional.of(new BigDecimal("230")));
        when(gameCoach.debrief(any())).thenReturn(new GameDebriefResult("You won by riding MSFT higher.", true));

        GameDebriefResponse response = gameService.getDebrief(ended.getSessionId(), owner);

        assertThat(response.aiGenerated()).isTrue();
        assertThat(response.summary()).isEqualTo("You won by riding MSFT higher.");
        assertThat(response.symbolPerformance()).hasSize(2);
        GameSymbolPerformanceResponse aapl = response.symbolPerformance().stream().filter(p -> p.symbol().equals("AAPL")).findFirst().orElseThrow();
        assertThat(aapl.realizedPnl()).isEqualByComparingTo("250");
        assertThat(aapl.quantityHeld()).isEqualByComparingTo("0");
        GameSymbolPerformanceResponse msftPnl = response.symbolPerformance().stream().filter(p -> p.symbol().equals("MSFT")).findFirst().orElseThrow();
        assertThat(msftPnl.unrealizedPnl()).isEqualByComparingTo("300");
        assertThat(msftPnl.quantityHeld()).isEqualByComparingTo("10");
    }

    @Test
    void getDebriefAwardsDebtFreeAchievementWhenWonWithNoLoans() {
        GameSession ended = GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-1").difficulty(GameDifficulty.APPRENTICE)
                .status(GameStatus.WON).cash(new BigDecimal("11000")).finalNetWorth(new BigDecimal("11000"))
                .startedAt(Instant.now().minusSeconds(900)).endsAt(Instant.now()).build();

        when(sessionRepository.findById(ended.getSessionId())).thenReturn(Optional.of(ended));
        when(tradeRepository.findBySessionIdOrderByCreatedAtDesc(ended.getSessionId())).thenReturn(List.of());
        when(positionRepository.findBySessionId(ended.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(ended.getSessionId())).thenReturn(List.of());
        when(gameCoach.debrief(any())).thenReturn(new GameDebriefResult("Clean win, no debt.", true));

        GameDebriefResponse response = gameService.getDebrief(ended.getSessionId(), owner);

        assertThat(response.achievements()).extracting("title").contains("Debt Free");
    }

    @Test
    void getDebriefAwardsHomeRunAndDayTraderAchievements() {
        GameSession ended = GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-1").difficulty(GameDifficulty.APPRENTICE)
                .status(GameStatus.WON).cash(new BigDecimal("11000")).finalNetWorth(new BigDecimal("11000"))
                .startedAt(Instant.now().minusSeconds(900)).endsAt(Instant.now()).build();
        // 10 trades total; one closes with a realized gain of 300 — over the 25%-of-£1,000-starting-cash
        // threshold (£250) that earns "Home Run", and the trade count alone earns "Day Trader".
        List<GameTrade> trades = new java.util.ArrayList<>();
        trades.add(GameTrade.builder().tradeId(UUID.randomUUID()).sessionId(ended.getSessionId()).symbol("AAPL")
                .side(OrderSide.SELL).quantity(new BigDecimal("5")).price(new BigDecimal("160")).fee(BigDecimal.ZERO)
                .realizedPnl(new BigDecimal("300")).createdAt(Instant.now().minusSeconds(500)).build());
        for (int i = 0; i < 9; i++) {
            trades.add(GameTrade.builder().tradeId(UUID.randomUUID()).sessionId(ended.getSessionId()).symbol("MSFT")
                    .side(OrderSide.BUY).quantity(new BigDecimal("1")).price(new BigDecimal("100")).fee(BigDecimal.ZERO)
                    .realizedPnl(null).createdAt(Instant.now().minusSeconds(400 - i)).build());
        }

        when(sessionRepository.findById(ended.getSessionId())).thenReturn(Optional.of(ended));
        when(tradeRepository.findBySessionIdOrderByCreatedAtDesc(ended.getSessionId())).thenReturn(trades);
        when(positionRepository.findBySessionId(ended.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(ended.getSessionId())).thenReturn(List.of());
        when(gameCoach.debrief(any())).thenReturn(new GameDebriefResult("Big win.", true));

        GameDebriefResponse response = gameService.getDebrief(ended.getSessionId(), owner);

        assertThat(response.achievements()).extracting("title").contains("Home Run", "Day Trader", "Debt Free");
    }

    @Test
    void getDebriefAwardsLessonLearnedAchievementWhenBankrupt() {
        GameSession ended = GameSession.builder()
                .sessionId(UUID.randomUUID()).clientId("client-1").difficulty(GameDifficulty.APPRENTICE)
                .status(GameStatus.LOST_BANKRUPT).cash(new BigDecimal("-500")).finalNetWorth(new BigDecimal("-500"))
                .startedAt(Instant.now().minusSeconds(900)).endsAt(Instant.now()).build();

        when(sessionRepository.findById(ended.getSessionId())).thenReturn(Optional.of(ended));
        when(tradeRepository.findBySessionIdOrderByCreatedAtDesc(ended.getSessionId())).thenReturn(List.of());
        when(positionRepository.findBySessionId(ended.getSessionId())).thenReturn(List.of());
        when(loanRepository.findBySessionId(ended.getSessionId())).thenReturn(List.of());
        when(gameCoach.debrief(any())).thenReturn(new GameDebriefResult("Bankrupt.", true));

        GameDebriefResponse response = gameService.getDebrief(ended.getSessionId(), owner);

        assertThat(response.achievements()).extracting("title").containsExactly("Lesson Learned");
    }

    @Test
    void getDebriefDeniedForNonOwner() {
        GameSession active = session(GameDifficulty.APPRENTICE, new BigDecimal("1000"), Instant.now().plusSeconds(600));
        when(sessionRepository.findById(active.getSessionId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> gameService.getDebrief(active.getSessionId(), otherClient)).isInstanceOf(AccessDeniedException.class);
    }
}
