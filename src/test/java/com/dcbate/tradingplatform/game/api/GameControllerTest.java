package com.dcbate.tradingplatform.game.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.domain.GameStatus;
import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.exception.GameInsufficientFundsException;
import com.dcbate.tradingplatform.exception.GameSessionNotFoundException;
import com.dcbate.tradingplatform.exception.GlobalExceptionHandler;
import com.dcbate.tradingplatform.game.api.dto.GameSessionResponse;
import com.dcbate.tradingplatform.game.api.dto.GameStartRequest;
import com.dcbate.tradingplatform.game.api.dto.GameStatsResponse;
import com.dcbate.tradingplatform.game.api.dto.GameTradeRequest;
import com.dcbate.tradingplatform.game.service.GameMarketService;
import com.dcbate.tradingplatform.game.service.GameService;
import com.dcbate.tradingplatform.game.service.GameSessionStartResult;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class GameControllerTest {

    @Mock
    private GameService gameService;

    @Mock
    private GameMarketService gameMarketService;

    private MockMvc mockMvc;
    private JsonMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders.standaloneSetup(new GameController(gameService, gameMarketService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .build();
    }

    private Authentication clientAuth(String clientId) {
        return new TestingAuthenticationToken(clientId, null, "ROLE_CLIENT");
    }

    private GameSessionResponse session(UUID sessionId, GameStatus status, BigDecimal cash) {
        return new GameSessionResponse(sessionId, "client-1", GameDifficulty.APPRENTICE, status, cash, BigDecimal.ZERO, cash,
                GameDifficulty.APPRENTICE.getGoalAmount(), 600, 0, Instant.now(), Instant.now().plusSeconds(600),
                Instant.now(), 1, false, null, null, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of());
    }

    @Test
    void listDifficultiesReturnsAllFourTiers() throws Exception {
        mockMvc.perform(get("/v1/game/difficulties").principal(clientAuth("client-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].code").value("APPRENTICE"));
    }

    @Test
    void startSessionReturnsCreatedForANewSession() throws Exception {
        UUID sessionId = UUID.randomUUID();
        GameStartRequest request = new GameStartRequest("client-1", GameDifficulty.APPRENTICE);
        when(gameService.startSession(eq("client-1"), eq(GameDifficulty.APPRENTICE), any()))
                .thenReturn(new GameSessionStartResult(session(sessionId, GameStatus.IN_PROGRESS, new BigDecimal("1000")), true));

        mockMvc.perform(post("/v1/game/sessions")
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.cash").value(1000));
    }

    @Test
    void startSessionReturnsOkWhenResumingAnExistingSession() throws Exception {
        UUID sessionId = UUID.randomUUID();
        GameStartRequest request = new GameStartRequest("client-1", GameDifficulty.APPRENTICE);
        when(gameService.startSession(eq("client-1"), eq(GameDifficulty.APPRENTICE), any()))
                .thenReturn(new GameSessionStartResult(session(sessionId, GameStatus.IN_PROGRESS, new BigDecimal("1000")), false));

        mockMvc.perform(post("/v1/game/sessions")
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.cash").value(1000));
    }

    @Test
    void getSessionReturnsNotFoundWhenMissing() throws Exception {
        UUID sessionId = UUID.randomUUID();
        when(gameService.getSession(eq(sessionId), any())).thenThrow(new GameSessionNotFoundException(sessionId));

        mockMvc.perform(get("/v1/game/sessions/{id}", sessionId).principal(clientAuth("client-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAnonymousGameModeRequestResolvesToTheGuestHeaderNotStaff() throws Exception {
        // The vulnerability this guards against: with no principal (a real guest, no JWT — exactly
        // how every Game Mode request arrives in production, since /v1/game/** is permitAll), the
        // controller must resolve a non-staff CallerPrincipal from X-Game-Client-Id, not fall
        // through to CallerPrincipal.from's null-is-staff dev-convenience branch, which would make
        // ownership checks a no-op for the entire feature.
        UUID sessionId = UUID.randomUUID();
        when(gameService.getSession(eq(sessionId), any())).thenReturn(session(sessionId, GameStatus.IN_PROGRESS, BigDecimal.TEN));

        mockMvc.perform(get("/v1/game/sessions/{id}", sessionId).header("X-Game-Client-Id", "guest-abc"))
                .andExpect(status().isOk());

        ArgumentCaptor<CallerPrincipal> callerCaptor = ArgumentCaptor.forClass(CallerPrincipal.class);
        verify(gameService).getSession(eq(sessionId), callerCaptor.capture());
        assertThat(callerCaptor.getValue().staff()).isFalse();
        assertThat(callerCaptor.getValue().clientId()).isEqualTo("guest-abc");
    }

    @Test
    void placeTradeReturnsConflictWhenFundsInsufficient() throws Exception {
        UUID sessionId = UUID.randomUUID();
        GameTradeRequest request = new GameTradeRequest("AAPL", OrderSide.BUY, new BigDecimal("100"));
        when(gameService.placeTrade(eq(sessionId), any(), any()))
                .thenThrow(new GameInsufficientFundsException(new BigDecimal("1000"), new BigDecimal("10")));

        mockMvc.perform(post("/v1/game/sessions/{id}/trades", sessionId)
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    void placeTradeReturnsConflictWhenTwoRequestsRaceOnTheSameSession() throws Exception {
        // Two overlapping requests against the same GameSession row (a double-click, a scripted
        // rapid double-submit) would otherwise silently clobber each other's cash update with no
        // @Version check — see GameSession.version's javadoc. Hibernate throws this instead, and it
        // must come back as a clean, retryable 409, not the raw Hibernate message or a 500.
        UUID sessionId = UUID.randomUUID();
        GameTradeRequest request = new GameTradeRequest("AAPL", OrderSide.BUY, new BigDecimal("10"));
        when(gameService.placeTrade(eq(sessionId), any(), any()))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(
                        "com.dcbate.tradingplatform.domain.GameSession", sessionId));

        mockMvc.perform(post("/v1/game/sessions/{id}/trades", sessionId)
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messages[0]").value("Something else changed this session at the same moment — try that again."));
    }

    @Test
    void getStatsReturnsOk() throws Exception {
        GameStatsResponse stats = new GameStatsResponse("client-1", 3, 1, 33.3, new BigDecimal("120.00"), List.of());
        when(gameService.getStats(eq("client-1"), any())).thenReturn(stats);

        mockMvc.perform(get("/v1/game/stats").param("clientId", "client-1").principal(clientAuth("client-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalGames").value(3))
                .andExpect(jsonPath("$.wins").value(1));
    }
}
