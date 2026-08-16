package com.dcbate.tradingplatform.ai.mcp;

import com.dcbate.tradingplatform.ai.GameCoach;
import com.dcbate.tradingplatform.ai.GameDebriefContext;
import com.dcbate.tradingplatform.ai.GameDebriefResult;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Calls bate-mcp-server's {@code debrief_game_session} tool instead of Anthropic directly — same
 * contract as the in-process {@code AnthropicGameCoach} it replaced: the session's win/loss
 * outcome is already decided by {@code GameServiceImpl.evaluate}, this only writes the debrief,
 * and a missing/unreachable server falls back to the rule-based summary computed from the same
 * session data.
 */
@Component
public class McpGameCoach implements GameCoach {

    private final McpToolClient mcpToolClient;

    public McpGameCoach(McpToolClient mcpToolClient) {
        this.mcpToolClient = mcpToolClient;
    }

    @Override
    public GameDebriefResult debrief(GameDebriefContext context) {
        return mcpToolClient.callTool("debrief_game_session", Map.of("narrative", context.narrative()))
                .map(text -> new GameDebriefResult(text, true))
                .orElseGet(() -> new GameDebriefResult(context.fallbackSummary(), false));
    }
}
