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
class GameDebriefToolTest {

    @Mock
    private ClaudeClient claudeClient;

    @Test
    void passesTheNarrativeThroughAndReturnsClaudesDebrief() {
        when(claudeClient.complete(contains("AAPL"), anyInt())).thenReturn("You won by riding AAPL higher.");

        String result = new GameDebriefTool(claudeClient).debriefGameSession("Outcome: WON. Trades: BUY 5 AAPL @ 150...");

        assertThat(result).isEqualTo("You won by riding AAPL higher.");
    }
}
