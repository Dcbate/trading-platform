package com.dcbate.batemcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;

import com.dcbate.batemcpserver.claude.ClaudeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentSummaryToolTest {

    @Mock
    private ClaudeClient claudeClient;

    @Test
    void passesTheContextThroughAndReturnsClaudesText() {
        when(claudeClient.complete(contains("settled successfully"), anyInt()))
                .thenReturn("Your payment of £250.00 to Acme Ltd has gone through.");

        String result = new PaymentSummaryTool(claudeClient).summarizePayment("payment of £250.00 to Acme Ltd settled successfully");

        assertThat(result).isEqualTo("Your payment of £250.00 to Acme Ltd has gone through.");
    }
}
