package com.dcbate.tradingplatform.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClaudeSummarizerImplTest {

    @Mock
    private McpToolClient mcpToolClient;

    @Test
    void returnsTheAiTextWhenTheToolCallSucceeds() {
        when(mcpToolClient.callTool("summarize_payment", Map.of("context", "payment failed")))
                .thenReturn(Optional.of("Unfortunately your payment could not be completed."));

        String result = new ClaudeSummarizerImpl(mcpToolClient).summarize("payment failed");

        assertThat(result).isEqualTo("Unfortunately your payment could not be completed.");
    }

    @Test
    void fallsBackToTheRawContextWhenTheToolCallFails() {
        when(mcpToolClient.callTool("summarize_payment", Map.of("context", "payment failed"))).thenReturn(Optional.empty());

        String result = new ClaudeSummarizerImpl(mcpToolClient).summarize("payment failed");

        assertThat(result).isEqualTo("payment failed");
    }
}
