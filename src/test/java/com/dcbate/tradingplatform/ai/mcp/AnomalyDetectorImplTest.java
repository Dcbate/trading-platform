package com.dcbate.tradingplatform.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.ai.AnomalyContext;
import com.dcbate.tradingplatform.ai.AnomalyResult;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnomalyDetectorImplTest {

    @Mock
    private McpToolClient mcpToolClient;

    @Test
    void returnsTheAiTextWhenTheToolCallSucceeds() {
        when(mcpToolClient.callTool("summarize_anomaly", Map.of("subject", "EUR/USD price", "description", "spiked 18%")))
                .thenReturn(Optional.of("This is a significant, tradeable move."));

        AnomalyResult result = new AnomalyDetectorImpl(mcpToolClient).explain(new AnomalyContext("EUR/USD price", "spiked 18%"));

        assertThat(result.explanation()).isEqualTo("This is a significant, tradeable move.");
        assertThat(result.aiEnriched()).isTrue();
    }

    @Test
    void fallsBackToTheRuleDescriptionWhenTheToolCallFails() {
        when(mcpToolClient.callTool("summarize_anomaly", Map.of("subject", "EUR/USD price", "description", "spiked 18%")))
                .thenReturn(Optional.empty());

        AnomalyResult result = new AnomalyDetectorImpl(mcpToolClient).explain(new AnomalyContext("EUR/USD price", "spiked 18%"));

        assertThat(result.explanation()).isEqualTo("spiked 18%");
        assertThat(result.aiEnriched()).isFalse();
    }
}
