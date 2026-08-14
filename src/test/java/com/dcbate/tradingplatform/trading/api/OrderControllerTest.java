package com.dcbate.tradingplatform.trading.api;

import static org.mockito.ArgumentMatchers.any;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(orderService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private OrderRequest validRequest() {
        return new OrderRequest("client-1", "EUR/USD", OrderSide.BUY, new BigDecimal("10"), new BigDecimal("150.00"));
    }

    @Test
    void submitOrderReturnsCreatedWithLocationHeader() throws Exception {
        UUID orderId = UUID.randomUUID();
        OrderResponse response = new OrderResponse(
                orderId, "client-1", "EUR/USD", OrderSide.BUY, new BigDecimal("10"), new BigDecimal("150.00"),
                OrderStatus.PENDING, Instant.now(), null);
        when(orderService.submitOrder(any())).thenReturn(response);

        mockMvc.perform(post("/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/v1/orders/" + orderId))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void submitOrderRejectsInvalidPayload() throws Exception {
        String invalidPayload = "{\"clientId\":\"\",\"currencyPair\":\"EUR/USD\",\"side\":\"BUY\",\"quantity\":-1,\"price\":150.00}";

        mockMvc.perform(post("/v1/orders").contentType(MediaType.APPLICATION_JSON).content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOrderReturnsNotFoundWhenMissing() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(orderService.getOrder(orderId)).thenThrow(new OrderNotFoundException(orderId));

        mockMvc.perform(get("/v1/orders/{id}", orderId)).andExpect(status().isNotFound());
    }
}
