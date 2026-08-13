package com.dcbate.tradingplatform.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.exception.GlobalExceptionHandler;
import com.dcbate.tradingplatform.exception.PaymentNotFoundException;
import com.dcbate.tradingplatform.payment.api.dto.PaymentRequest;
import com.dcbate.tradingplatform.payment.api.dto.PaymentResponse;
import com.dcbate.tradingplatform.payment.service.PaymentService;
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
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private PaymentRequest validRequest() {
        return new PaymentRequest("client-1", new BigDecimal("100.00"), "key-1", "US");
    }

    @Test
    void submitPaymentReturnsAccepted() throws Exception {
        PaymentResponse response = new PaymentResponse(
                UUID.randomUUID(), "client-1", new BigDecimal("100.00"), "US", PaymentStatus.PENDING, Instant.now());
        when(paymentService.submitPayment(any())).thenReturn(response);

        mockMvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void submitPaymentRejectsInvalidCountry() throws Exception {
        String invalidPayload = "{\"clientId\":\"c1\",\"amount\":100.00,\"idempotencyKey\":\"k1\",\"country\":\"USA\"}";

        mockMvc.perform(post("/v1/payments").contentType(MediaType.APPLICATION_JSON).content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPaymentReturnsNotFoundWhenMissing() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.getPayment(paymentId)).thenThrow(new PaymentNotFoundException(paymentId));

        mockMvc.perform(get("/v1/payments/{id}", paymentId)).andExpect(status().isNotFound());
    }
}
