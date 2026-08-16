package com.dcbate.tradingplatform.ai.mcp;

import com.dcbate.tradingplatform.ai.ClaudeSummarizer;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Calls bate-mcp-server's {@code summarize_payment} tool instead of Anthropic directly — same
 * contract as the in-process {@code AnthropicClaudeSummarizer} it replaced: the payment's real
 * outcome is already decided, this only writes the notification text, and a missing/unreachable
 * server falls back to the raw context so the notification still sends.
 */
@Component
public class ClaudeSummarizerImpl implements ClaudeSummarizer {

    private final McpToolClient mcpToolClient;

    public ClaudeSummarizerImpl(McpToolClient mcpToolClient) {
        this.mcpToolClient = mcpToolClient;
    }

    @Override
    public String summarize(String context) {
        return mcpToolClient.callTool("summarize_payment", Map.of("context", context)).orElse(context);
    }
}
