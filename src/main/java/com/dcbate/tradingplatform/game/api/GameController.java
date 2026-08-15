package com.dcbate.tradingplatform.game.api;

import com.dcbate.tradingplatform.domain.GameDifficulty;
import com.dcbate.tradingplatform.game.api.dto.GameDifficultyResponse;
import com.dcbate.tradingplatform.game.api.dto.GameLoanRequest;
import com.dcbate.tradingplatform.game.api.dto.GamePriceResponse;
import com.dcbate.tradingplatform.game.api.dto.GameSessionResponse;
import com.dcbate.tradingplatform.game.api.dto.GameStartRequest;
import com.dcbate.tradingplatform.game.api.dto.GameStatsResponse;
import com.dcbate.tradingplatform.game.api.dto.GameTradeRequest;
import com.dcbate.tradingplatform.game.api.dto.GameTradeResponse;
import com.dcbate.tradingplatform.game.service.GameMarketService;
import com.dcbate.tradingplatform.game.service.GameService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "The 4 difficulty tiers and their parameters")
    public ResponseEntity<List<GameDifficultyResponse>> listDifficulties() {
        return ResponseEntity.ok(Arrays.stream(GameDifficulty.values()).map(GameDifficultyResponse::from).toList());
    }

    @GetMapping("/market")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Live simulated prices for a difficulty tier's market")
    public ResponseEntity<List<GamePriceResponse>> getMarket(@RequestParam GameDifficulty difficulty) {
        List<GamePriceResponse> prices = gameMarketService.currentPrices(difficulty).entrySet().stream()
                .map(e -> new GamePriceResponse(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(GamePriceResponse::symbol))
                .toList();
        return ResponseEntity.ok(prices);
    }

    @PostMapping("/sessions")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Start a new Game Mode session, or resume the caller's existing in-progress one")
    public ResponseEntity<GameSessionResponse> startSession(@Valid @RequestBody GameStartRequest request, Authentication authentication) {
        return ResponseEntity.ok(gameService.startSession(request.difficulty(), CallerPrincipal.from(authentication)));
    }

    @GetMapping("/sessions/{sessionId}")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Live valuation of a session — cash, positions, loans, net worth, time remaining")
    public ResponseEntity<GameSessionResponse> getSession(@PathVariable UUID sessionId, Authentication authentication) {
        return ResponseEntity.ok(gameService.getSession(sessionId, CallerPrincipal.from(authentication)));
    }

    @PostMapping("/sessions/{sessionId}/loans")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Take a loan within a session — no repayment flow, its balance is netted out of the final score")
    public ResponseEntity<GameSessionResponse> takeLoan(
            @PathVariable UUID sessionId, @Valid @RequestBody GameLoanRequest request, Authentication authentication) {
        return ResponseEntity.ok(gameService.takeLoan(sessionId, request, CallerPrincipal.from(authentication)));
    }

    @PostMapping("/sessions/{sessionId}/trades")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Buy or sell at the current simulated market price — fills instantly, no order book")
    public ResponseEntity<GameSessionResponse> placeTrade(
            @PathVariable UUID sessionId, @Valid @RequestBody GameTradeRequest request, Authentication authentication) {
        return ResponseEntity.ok(gameService.placeTrade(sessionId, request, CallerPrincipal.from(authentication)));
    }

    @GetMapping("/sessions/{sessionId}/trades")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "A session's trade history, most recent first")
    public ResponseEntity<List<GameTradeResponse>> listTrades(@PathVariable UUID sessionId, Authentication authentication) {
        return ResponseEntity.ok(gameService.listTrades(sessionId, CallerPrincipal.from(authentication)));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "A client's own Game Mode history — win rate, best score per difficulty, best trade. Personal only, no other players.")
    public ResponseEntity<GameStatsResponse> getStats(@RequestParam String clientId, Authentication authentication) {
        return ResponseEntity.ok(gameService.getStats(clientId, CallerPrincipal.from(authentication)));
    }
}
