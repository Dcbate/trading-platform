package com.dcbate.tradingplatform.trading.api;

import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.trading.api.dto.PriceResponse;
import com.dcbate.tradingplatform.trading.service.PriceFeedService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the bank's live (synthetic) price feed — public, like the currency/loan
 * catalogs, since it's published market data rather than a client resource. Backed by the same
 * {@link PriceFeedService} cache real conversions and stock order settlement read from, so what
 * these endpoints show is exactly what a conversion or a fill would actually use.
 */
@RestController
@RequiredArgsConstructor
public class PriceController {

    private final PriceFeedService priceFeedService;
    private final TradingProperties tradingProperties;

    @GetMapping("/v1/fx/prices")
    @Tag(name = "FX", description = "Live (synthetic) currency pair prices")
    @Operation(summary = "List the current cached price for every currency pair the FX desk trades")
    public ResponseEntity<List<PriceResponse>> listFxPrices() {
        return ResponseEntity.ok(pricesFor(tradingProperties.currencyPairs()));
    }

    @GetMapping("/v1/stocks/prices")
    @Tag(name = "Stocks", description = "Live (synthetic) stock prices")
    @Operation(summary = "List the current cached price for every stock symbol the bank offers")
    public ResponseEntity<List<PriceResponse>> listStockPrices() {
        return ResponseEntity.ok(pricesFor(tradingProperties.stockSymbols()));
    }

    private List<PriceResponse> pricesFor(List<String> symbols) {
        return symbols.stream()
                .flatMap(symbol -> priceFeedService.currentPrice(symbol).map(price -> new PriceResponse(symbol, price)).stream())
                .toList();
    }
}
