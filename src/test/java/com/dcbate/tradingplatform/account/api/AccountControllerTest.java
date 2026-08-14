package com.dcbate.tradingplatform.account.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dcbate.tradingplatform.account.api.dto.AccountRequest;
import com.dcbate.tradingplatform.account.api.dto.AccountResponse;
import com.dcbate.tradingplatform.account.service.AccountService;
import com.dcbate.tradingplatform.config.TradingProperties;
import com.dcbate.tradingplatform.domain.AccountStatus;
import com.dcbate.tradingplatform.domain.AccountType;
import com.dcbate.tradingplatform.exception.AccountNotFoundException;
import com.dcbate.tradingplatform.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
class AccountControllerTest {

    @Mock
    private AccountService accountService;

    private MockMvc mockMvc;
    private JsonMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        TradingProperties tradingProperties = new TradingProperties(
                List.of("EUR/USD", "GBP/USD", "USD/JPY", "USD/CHF", "AUD/USD"),
                new TradingProperties.PriceFeed(2000, new BigDecimal("10")));
        mockMvc = MockMvcBuilders.standaloneSetup(new AccountController(accountService, tradingProperties))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .build();
    }

    private Authentication clientAuth(String clientId) {
        return new TestingAuthenticationToken(clientId, null, "ROLE_CLIENT");
    }

    @Test
    void listCurrenciesReturnsDistinctSortedCodesFromCurrencyPairs() throws Exception {
        mockMvc.perform(get("/v1/accounts/currencies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0]").value("AUD"))
                .andExpect(jsonPath("$[1]").value("CHF"));
    }

    @Test
    void openAccountReturnsCreated() throws Exception {
        AccountRequest request = new AccountRequest("client-1", AccountType.CHECKING, "USD", null, new BigDecimal("100.00"));
        AccountResponse response = new AccountResponse(
                UUID.randomUUID(), "client-1", AccountType.CHECKING, "USD", null, new BigDecimal("100.00"), AccountStatus.ACTIVE, Instant.now());
        when(accountService.openAccount(any(), any())).thenReturn(response);

        mockMvc.perform(post("/v1/accounts")
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void openAccountRejectsInvalidCurrency() throws Exception {
        String invalidPayload = "{\"clientId\":\"c1\",\"accountType\":\"CHECKING\",\"currency\":\"US\",\"openingBalance\":100.00}";

        mockMvc.perform(post("/v1/accounts")
                        .principal(clientAuth("c1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAccountReturnsNotFoundWhenMissing() throws Exception {
        UUID accountId = UUID.randomUUID();
        when(accountService.getAccount(eq(accountId), any())).thenThrow(new AccountNotFoundException(accountId));

        mockMvc.perform(get("/v1/accounts/{id}", accountId).principal(clientAuth("client-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listAccountsReturnsOk() throws Exception {
        when(accountService.listAccountsForClient(eq("client-1"), any())).thenReturn(java.util.List.of());

        mockMvc.perform(get("/v1/accounts").param("clientId", "client-1").principal(clientAuth("client-1")))
                .andExpect(status().isOk());
    }
}
