package com.dcbate.tradingplatform.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.dcbate.tradingplatform.ai.GameDebriefContext;
import com.dcbate.tradingplatform.ai.GameDebriefResult;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GameCoachImplTest {

    @Mock
    private McpToolClient mcpToolClient;

    @Test
    void returnsTheAiDebriefWhenTheToolCallSucceeds() {
        when(mcpToolClient.callTool("debrief_game_session", Map.of("narrative", "Outcome: WON")))
                .thenReturn(Optional.of("You won by riding AAPL higher."));

        GameDebriefResult result = new GameCoachImpl(mcpToolClient).debrief(new GameDebriefContext("Outcome: WON", "fallback summary"));

        assertThat(result.summary()).isEqualTo("You won by riding AAPL higher.");
        assertThat(result.aiGenerated()).isTrue();
    }

    @Test
    void fallsBackToTheRuleBasedSummaryWhenTheToolCallFails() {
        when(mcpToolClient.callTool("debrief_game_session", Map.of("narrative", "Outcome: WON"))).thenReturn(Optional.empty());

        GameDebriefResult result = new GameCoachImpl(mcpToolClient).debrief(new GameDebriefContext("Outcome: WON", "fallback summary"));

        assertThat(result.summary()).isEqualTo("fallback summary");
        assertThat(result.aiGenerated()).isFalse();
    }
}
