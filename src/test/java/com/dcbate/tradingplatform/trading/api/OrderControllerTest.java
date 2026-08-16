package com.dcbate.tradingplatform.trading.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dcbate.tradingplatform.domain.OrderSide;
import com.dcbate.tradingplatform.domain.OrderStatus;
import com.dcbate.tradingplatform.exception.GlobalExceptionHandler;
import com.dcbate.tradingplatform.exception.OrderNotFoundException;
import com.dcbate.tradingplatform.trading.api.dto.OrderRequest;
import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import com.dcbate.tradingplatform.trading.service.OrderService;
import java.math.BigDecimal;
import java.time.Instant;
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
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    private MockMvc mockMvc;
    private JsonMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().findAndAddModules().build();
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(objectMapper))
                .build();
    }

    private Authentication clientAuth(String clientId) {
        return new TestingAuthenticationToken(clientId, null, "ROLE_CLIENT");
    }

    private OrderRequest validRequest() {
        return new OrderRequest("client-1", "EUR/USD", OrderSide.BUY, new BigDecimal("10"), new BigDecimal("150.00"), null);
    }

    @Test
    void submitOrderReturnsCreatedWithLocationHeader() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderResponse response = new OrderResponse(
                orderId, "client-1", null, "EUR/USD", OrderSide.BUY, new BigDecimal("10"), new BigDecimal("150.00"),
                OrderStatus.PENDING, Instant.now(), null);
        when(orderService.submitOrder(any(), any())).thenReturn(response);

        mockMvc.perform(post("/v1/orders")
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/orders/" + orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void submitOrderRejectsInvalidPayload() throws Exception {
        String invalidPayload = "{\"clientId\":\"\",\"currencyPair\":\"EUR/USD\",\"side\":\"BUY\",\"quantity\":-1,\"price\":150.00}";

        mockMvc.perform(post("/v1/orders").principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON).content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOrderReturnsNotFoundWhenMissing() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderService.getOrder(eq(orderId), any())).thenThrow(new OrderNotFoundException(orderId));

        mockMvc.perform(get("/v1/orders/{id}", orderId).principal(clientAuth("client-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void getOrderRouteDoesNotSwallowTheWebSocketStreamPath() throws Exception {
        // WebSocketConfig registers /v1/orders/stream separately — an unconstrained {orderId}
        // path variable here would match "stream" as a literal segment and shadow that handler
        // (RequestMappingHandlerMapping is checked before the WebSocket handler mapping), so this
        // pins the fix: the UUID-shaped constraint must leave "stream" genuinely unmatched.
        mockMvc.perform(get("/v1/orders/stream").principal(clientAuth("client-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void listOrdersReturnsOk() throws Exception {
        when(orderService.listOrdersForClient(eq("client-1"), any())).thenReturn(java.util.List.of());

        mockMvc.perform(get("/v1/orders").param("clientId", "client-1").principal(clientAuth("client-1")))
                .andExpect(status().isOk());
    }
}
