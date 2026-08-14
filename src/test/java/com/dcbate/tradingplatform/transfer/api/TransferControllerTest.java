package com.dcbate.tradingplatform.transfer.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dcbate.tradingplatform.domain.TransferStatus;
import com.dcbate.tradingplatform.exception.GlobalExceptionHandler;
import com.dcbate.tradingplatform.exception.TransferNotFoundException;
import com.dcbate.tradingplatform.transfer.api.dto.TransferRequest;
import com.dcbate.tradingplatform.transfer.api.dto.TransferResponse;
import com.dcbate.tradingplatform.transfer.service.TransferService;
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
class TransferControllerTest {

    @Mock
    private TransferService transferService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new TransferController(transferService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private Authentication clientAuth(String clientId) {
        return new TestingAuthenticationToken(clientId, null, "ROLE_CLIENT");
    }

    @Test
    void transferReturnsCreated() throws Exception {
        TransferRequest request = new TransferRequest(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("40.00"));
        TransferResponse response = new TransferResponse(
                UUID.randomUUID(), request.fromAccountId(), request.toAccountId(), "client-1", "client-2",
                new BigDecimal("40.00"), TransferStatus.COMPLETED, Instant.now());
        when(transferService.transfer(any(), any())).thenReturn(response);

        mockMvc.perform(post("/v1/transfers")
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void transferRejectsNonPositiveAmount() throws Exception {
        String invalidPayload = "{\"fromAccountId\":\"" + UUID.randomUUID() + "\",\"toAccountId\":\""
                + UUID.randomUUID() + "\",\"amount\":0}";

        mockMvc.perform(post("/v1/transfers")
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getTransferReturnsNotFoundWhenMissing() throws Exception {
        UUID transferId = UUID.randomUUID();
        when(transferService.getTransfer(eq(transferId), any())).thenThrow(new TransferNotFoundException(transferId));

        mockMvc.perform(get("/v1/transfers/{id}", transferId).principal(clientAuth("client-1")))
                .andExpect(status().isNotFound());
    }
}
