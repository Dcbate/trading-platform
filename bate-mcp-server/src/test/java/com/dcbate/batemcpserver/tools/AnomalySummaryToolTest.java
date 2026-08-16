package com.dcbate.batemcpserver.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.dcbate.batemcpserver.claude.ClaudeClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AnomalySummaryToolTest {

    @Mock
    private ClaudeClient claudeClient;

    @Test
    void buildsAPromptFromSubjectAndDescriptionAndReturnsClaudesText() {
        when(claudeClient.complete(contains("EUR/USD"), anyInt())).thenReturn("This is a notable but not extreme move.");

        String result = new AnomalySummaryTool(claudeClient)
                .summarizeAnomaly("EUR/USD price", "moved 18% in one tick, threshold is 15%");

        assertThat(result).isEqualTo("This is a notable but not extreme move.");
        verify(claudeClient).complete(contains("moved 18% in one tick"), anyInt());
    }
}
