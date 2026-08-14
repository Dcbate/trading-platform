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
 * Read-only view of the FX desk's live (synthetic) price feed — public, like the currency/loan
 * catalogs, since it's published market data rather than a client resource. Backed by the same
 * {@link PriceFeedService} cache {@code AccountServiceImpl.convert()} reads for real conversions,
 * so what this endpoint shows is exactly what a conversion would actually use.
 */
@RestController
@RequestMapping("/v1/fx")
@RequiredArgsConstructor
@Tag(name = "FX", description = "Live (synthetic) currency pair prices")
public class PriceController {

    private final PriceFeedService priceFeedService;
    private final TradingProperties tradingProperties;

    @GetMapping("/prices")
    @Operation(summary = "List the current cached price for every currency pair the FX desk trades")
    public ResponseEntity<List<PriceResponse>> listPrices() {
        List<PriceResponse> prices = tradingProperties.currencyPairs().stream()
                .flatMap(pair -> priceFeedService.currentPrice(pair).map(price -> new PriceResponse(pair, price)).stream())
                .toList();
        return ResponseEntity.ok(prices);
    }
}
