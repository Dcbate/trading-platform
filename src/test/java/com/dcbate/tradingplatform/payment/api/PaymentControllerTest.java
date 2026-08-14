package com.dcbate.tradingplatform.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dcbate.tradingplatform.domain.PaymentStatus;
import com.dcbate.tradingplatform.exception.GlobalExceptionHandler;
import com.dcbate.tradingplatform.exception.InvalidPaymentStateException;
import com.dcbate.tradingplatform.exception.PaymentNotFoundException;
import com.dcbate.tradingplatform.payment.api.dto.PaymentRequest;
import com.dcbate.tradingplatform.payment.api.dto.PaymentResponse;
import com.dcbate.tradingplatform.payment.service.FraudDetectionService;
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
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PaymentControllerTest {

    @Mock
    private PaymentService paymentService;

    @Mock
    private FraudDetectionService fraudDetectionService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new PaymentController(paymentService, fraudDetectionService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private PaymentRequest validRequest() {
        return new PaymentRequest("client-1", UUID.randomUUID(), new BigDecimal("100.00"), "key-1", "US");
    }

    private Authentication clientAuth(String clientId) {
        return new TestingAuthenticationToken(clientId, null, "ROLE_CLIENT");
    }

    @Test
    void submitPaymentReturnsAccepted() throws Exception {
        PaymentResponse response = new PaymentResponse(
                UUID.randomUUID(), "client-1", UUID.randomUUID(), new BigDecimal("100.00"), "US", PaymentStatus.PENDING, Instant.now());
        when(paymentService.submitPayment(any(), any())).thenReturn(response);

        mockMvc.perform(post("/v1/payments")
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void submitPaymentRejectsInvalidCountry() throws Exception {
        String invalidPayload = "{\"clientId\":\"c1\",\"sourceAccountId\":\"" + UUID.randomUUID()
                + "\",\"amount\":100.00,\"idempotencyKey\":\"k1\",\"country\":\"USA\"}";

        mockMvc.perform(post("/v1/payments")
                        .principal(clientAuth("c1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPaymentReturnsNotFoundWhenMissing() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.getPayment(eq(paymentId), any())).thenThrow(new PaymentNotFoundException(paymentId));

        mockMvc.perform(get("/v1/payments/{id}", paymentId).principal(clientAuth("client-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void approvePaymentReturnsNoContent() throws Exception {
        UUID paymentId = UUID.randomUUID();

        mockMvc.perform(post("/v1/payments/{id}/approve", paymentId)).andExpect(status().isNoContent());

        verify(fraudDetectionService).approve(paymentId);
    }

    @Test
    void rejectPaymentReturnsNoContent() throws Exception {
        UUID paymentId = UUID.randomUUID();

        mockMvc.perform(post("/v1/payments/{id}/reject", paymentId)).andExpect(status().isNoContent());

        verify(fraudDetectionService).reject(paymentId);
    }

    @Test
    void approvePaymentReturnsConflictWhenNotUnderReview() throws Exception {
        UUID paymentId = UUID.randomUUID();
        doThrow(new InvalidPaymentStateException(paymentId, PaymentStatus.UNDER_REVIEW, PaymentStatus.SETTLED))
                .when(fraudDetectionService).approve(paymentId);

        mockMvc.perform(post("/v1/payments/{id}/approve", paymentId)).andExpect(status().isConflict());
    }
}
