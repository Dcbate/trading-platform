package com.dcbate.tradingplatform.game.api;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.game.api.dto.GameDebriefResponse;
import com.dcbate.tradingplatform.game.api.dto.GameDifficultyResponse;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRepayRequest;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRequest;
import com.dcbate.tradingplatform.game.api.dto.GamePriceResponse;
import com.dcbate.tradingplatform.game.api.dto.GameSessionResponse;
import com.dcbate.tradingplatform.game.api.dto.GameStartRequest;
import com.dcbate.tradingplatform.game.api.dto.GameStatsResponse;
import com.dcbate.tradingplatform.game.api.dto.GameTradeRequest;
import com.dcbate.tradingplatform.game.api.dto.GameTradeResponse;
import com.dcbate.tradingplatform.game.service.GameMarketService;
import com.dcbate.tradingplatform.game.service.GameService;
import com.dcbate.tradingplatform.game.service.GameSessionStartResult;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Game Mode — a self-contained simulated economy for practicing loans/FX/stock trading against a
 * money goal within a time limit. Entirely separate from every other controller in the app: no
 * shared tables, no shared Kafka topics, nothing here can touch a real account.
 *
 * <p>No {@code @PreAuthorize} on any method here, unlike every other client-facing controller —
 * deliberately, not an oversight. Game Mode is playable without logging in (see
 * {@code SecurityConfig}'s {@code /v1/game/**} permitAll entry and docs/GAME_MODE.md §6/§7): an
 * anonymous caller has no {@code CLIENT}/{@code ADMIN} role to check, so requiring one here would
 * just lock guests out. {@link CallerPrincipal#from} still resolves a real identity when a JWT is
 * present, so a logged-in client's calls are attributed to their real account exactly as
 * elsewhere; {@link GameServiceImpl#startSession} is what actually ties a session to whichever
 * {@code clientId} the caller (real or a client-generated guest id) supplies.
 */
@Slf4j
@RestController
@RequestMapping("/v1/game")
@RequiredArgsConstructor
@Tag(name = "Game Mode", description = "Simulated trading game — loans, FX, and stocks against a money goal")
public class GameController {

    private final GameService gameService;
    private final GameMarketService gameMarketService;

    @GetMapping("/difficulties")
    @Operation(summary = "The 4 difficulty tiers and their parameters")
    public ResponseEntity<List<GameDifficultyResponse>> listDifficulties() {
        return ResponseEntity.ok(Arrays.stream(GameDifficulty.values()).map(GameDifficultyResponse::from).toList());
    }

    @GetMapping("/market")
    @Operation(summary = "Live simulated prices for a difficulty tier's market")
    public ResponseEntity<List<GamePriceResponse>> getMarket(@RequestParam GameDifficulty difficulty) {
        List<GamePriceResponse> prices = gameMarketService.currentPrices(difficulty).entrySet().stream()
                .map(e -> new GamePriceResponse(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(GamePriceResponse::symbol))
                .toList();
        return ResponseEntity.ok(prices);
    }

    @PostMapping("/sessions")
    @Operation(summary = "Start a new Game Mode session, or resume the caller's existing in-progress one")
    public ResponseEntity<GameSessionResponse> startSession(@Valid @RequestBody GameStartRequest request, Authentication authentication) {
        GameSessionStartResult result = gameService.startSession(request.clientId(), request.difficulty(), CallerPrincipal.from(authentication));
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.session());
    }

    @GetMapping("/sessions/{sessionId}")
    @Operation(summary = "Live valuation of a session — cash, positions, loans, net worth, time remaining")
    public ResponseEntity<GameSessionResponse> getSession(@PathVariable UUID sessionId, Authentication authentication) {
        return ResponseEntity.ok(gameService.getSession(sessionId, CallerPrincipal.from(authentication)));
    }

    @PostMapping("/sessions/{sessionId}/loans")
    @Operation(summary = "Take a loan within a session — interest accrues per minute held until repaid or the game ends")
    public ResponseEntity<GameSessionResponse> takeLoan(
            @PathVariable UUID sessionId, @Valid @RequestBody GameLoanRequest request, Authentication authentication) {
        GameSessionResponse response = gameService.takeLoan(sessionId, request, CallerPrincipal.from(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/sessions/{sessionId}/loans/{loanId}/repay")
    @Operation(summary = "Repay a game loan — applied to accrued interest first, then outstanding principal")
    public ResponseEntity<GameSessionResponse> repayLoan(
            @PathVariable UUID sessionId, @PathVariable UUID loanId, @Valid @RequestBody GameLoanRepayRequest request, Authentication authentication) {
        return ResponseEntity.ok(gameService.repayLoan(sessionId, loanId, request, CallerPrincipal.from(authentication)));
    }

    @PostMapping("/sessions/{sessionId}/trades")
    @Operation(summary = "Buy or sell at the current simulated market price — fills instantly, no order book")
    public ResponseEntity<GameSessionResponse> placeTrade(
            @PathVariable UUID sessionId, @Valid @RequestBody GameTradeRequest request, Authentication authentication) {
        GameSessionResponse response = gameService.placeTrade(sessionId, request, CallerPrincipal.from(authentication));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/sessions/{sessionId}/trades")
    @Operation(summary = "A session's trade history, most recent first")
    public ResponseEntity<List<GameTradeResponse>> listTrades(@PathVariable UUID sessionId, Authentication authentication) {
        return ResponseEntity.ok(gameService.listTrades(sessionId, CallerPrincipal.from(authentication)));
    }

    @GetMapping("/sessions/{sessionId}/debrief")
    @Operation(summary = "AI-written debrief for a finished session — why you won or lost, which trades/loans helped or hurt, "
            + "plus a per-symbol P&L breakdown. 409 if the session hasn't ended yet.")
    public ResponseEntity<GameDebriefResponse> getDebrief(@PathVariable UUID sessionId, Authentication authentication) {
        return ResponseEntity.ok(gameService.getDebrief(sessionId, CallerPrincipal.from(authentication)));
    }

    @GetMapping("/stats")
    @Operation(summary = "A client's own Game Mode history — win rate, best score per difficulty, best trade. Personal only, no other players.")
    public ResponseEntity<GameStatsResponse> getStats(@RequestParam String clientId, Authentication authentication) {
        return ResponseEntity.ok(gameService.getStats(clientId, CallerPrincipal.from(authentication)));
    }
}
