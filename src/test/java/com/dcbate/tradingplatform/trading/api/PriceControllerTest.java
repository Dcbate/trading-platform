package com.dcbate.tradingplatform.trading.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.trading.service.PriceFeedService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PriceControllerTest {

    @Mock
    private PriceFeedService priceFeedService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TradingProperties tradingProperties = new TradingProperties(
                List.of("EUR/USD", "GBP/USD", "USD/JPY"), List.of("AAPL"), new TradingProperties.PriceFeed(2000, new BigDecimal("10")));
        mockMvc = MockMvcBuilders.standaloneSetup(new PriceController(priceFeedService, tradingProperties)).build();
    }

    @Test
    void listFxPricesReturnsOnlyPairsWithACachedPrice() throws Exception {
        when(priceFeedService.currentPrice("EUR/USD")).thenReturn(Optional.of(new BigDecimal("1.0800")));
        when(priceFeedService.currentPrice("GBP/USD")).thenReturn(Optional.of(new BigDecimal("1.2700")));
        when(priceFeedService.currentPrice("USD/JPY")).thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/fx/prices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].symbol").value("EUR/USD"))
                .andExpect(jsonPath("$[0].price").value(1.08));
    }

    @Test
    void listStockPricesReturnsOnlyPricedSymbols() throws Exception {
        when(priceFeedService.currentPrice("AAPL")).thenReturn(Optional.of(new BigDecimal("190.00")));

        mockMvc.perform(get("/v1/stocks/prices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].price").value(190.00));
    }
}
