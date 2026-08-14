package com.dcbate.tradingplatform.loan.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.dcbate.tradingplatform.domain.LoanStatus;
import com.dcbate.tradingplatform.exception.GlobalExceptionHandler;
import com.dcbate.tradingplatform.exception.LoanNotActiveException;
import com.dcbate.tradingplatform.exception.LoanNotFoundException;
import com.dcbate.tradingplatform.loan.api.dto.LoanRequest;
import com.dcbate.tradingplatform.loan.api.dto.LoanResponse;
import com.dcbate.tradingplatform.loan.service.LoanService;
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
class LoanControllerTest {

    @Mock
    private LoanService loanService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new LoanController(loanService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .build();
    }

    private Authentication clientAuth(String clientId) {
        return new TestingAuthenticationToken(clientId, null, "ROLE_CLIENT");
    }

    private LoanResponse response(UUID loanId, LoanStatus status, BigDecimal outstanding, BigDecimal accrued) {
        return new LoanResponse(loanId, "client-1", UUID.randomUUID(), new BigDecimal("1000.00"),
                outstanding, new BigDecimal("5.0"), accrued, status, Instant.now());
    }

    @Test
    void originateReturnsCreated() throws Exception {
        LoanRequest request = new LoanRequest("client-1", UUID.randomUUID(), new BigDecimal("1000.00"), new BigDecimal("5.0"));
        when(loanService.originate(any(), any())).thenReturn(response(UUID.randomUUID(), LoanStatus.ACTIVE, new BigDecimal("1000.00"), BigDecimal.ZERO));

        mockMvc.perform(post("/v1/loans")
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void getLoanReturnsNotFoundWhenMissing() throws Exception {
        UUID loanId = UUID.randomUUID();
        when(loanService.getLoan(eq(loanId), any())).thenThrow(new LoanNotFoundException(loanId));

        mockMvc.perform(get("/v1/loans/{id}", loanId).principal(clientAuth("client-1")))
                .andExpect(status().isNotFound());
    }

    @Test
    void repayReturnsOk() throws Exception {
        UUID loanId = UUID.randomUUID();
        when(loanService.repay(eq(loanId), any(), any())).thenReturn(response(loanId, LoanStatus.ACTIVE, new BigDecimal("950.00"), BigDecimal.ZERO));

        mockMvc.perform(post("/v1/loans/{id}/repay", loanId)
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outstandingPrincipal").value(950.00));
    }

    @Test
    void repayReturnsConflictWhenLoanNotActive() throws Exception {
        UUID loanId = UUID.randomUUID();
        when(loanService.repay(eq(loanId), any(), any())).thenThrow(new LoanNotActiveException(loanId));

        mockMvc.perform(post("/v1/loans/{id}/repay", loanId)
                        .principal(clientAuth("client-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":50.00}"))
                .andExpect(status().isConflict());
    }
}
