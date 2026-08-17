package com.dcbate.tradingplatform.crypto.api;

import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.crypto.api.dto.CryptoTradeRequest;
import com.dcbate.tradingplatform.crypto.api.dto.CryptoTradeResponse;
import com.dcbate.tradingplatform.crypto.service.CryptoTradeService;
import com.dcbate.tradingplatform.security.CallerPrincipal;
import com.dcbate.tradingplatform.trading.api.dto.PriceResponse;
import com.dcbate.tradingplatform.trading.service.PriceFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Crypto — its own controller, not folded into the FX/stock trading desk, because it settles a
 * genuinely different way. See {@code CryptoTradeServiceImpl}'s javadoc and
 * {@code docs/CRYPTO.md} for why.
 */
@RestController
@RequestMapping("/v1/crypto")
@RequiredArgsConstructor
@Tag(name = "Crypto", description = "Instantly-settled crypto trading, quoted in GBP")
public class CryptoController {

    private final CryptoTradeService cryptoTradeService;
    private final PriceFeedService priceFeedService;
    private final TradingProperties tradingProperties;

    @GetMapping("/prices")
    @Operation(summary = "List the current cached price for every crypto pair the bank offers, in GBP")
    public ResponseEntity<List<PriceResponse>> listPrices() {
        List<PriceResponse> prices = tradingProperties.cryptoSymbols().stream()
                .flatMap(symbol -> priceFeedService.currentPrice(symbol).map(price -> new PriceResponse(symbol, price)).stream())
                .toList();
        return ResponseEntity.ok(prices);
    }

    @PostMapping("/{accountId}/buy")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Buy crypto — settles instantly against the live price, no counterparty order needed")
    public ResponseEntity<CryptoTradeResponse> buy(
            @PathVariable UUID accountId, @Valid @RequestBody CryptoTradeRequest request, Authentication authentication) {
        return ResponseEntity.ok(cryptoTradeService.buy(accountId, request, CallerPrincipal.from(authentication)));
    }

    @PostMapping("/{accountId}/sell")
    @PreAuthorize("hasAnyRole('CLIENT', 'ADMIN')")
    @Operation(summary = "Sell crypto — settles instantly against the live price")
    public ResponseEntity<CryptoTradeResponse> sell(
            @PathVariable UUID accountId, @Valid @RequestBody CryptoTradeRequest request, Authentication authentication) {
        return ResponseEntity.ok(cryptoTradeService.sell(accountId, request, CallerPrincipal.from(authentication)));
    }
}
