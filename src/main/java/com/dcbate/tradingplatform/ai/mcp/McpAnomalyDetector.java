package com.dcbate.tradingplatform.ai.mcp;

import com.dcbate.tradingplatform.ai.AnomalyContext;
import com.dcbate.tradingplatform.ai.AnomalyDetector;
import com.dcbate.tradingplatform.ai.AnomalyResult;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Calls bate-mcp-server's {@code summarize_anomaly} tool instead of Anthropic directly — same
 * contract as the in-process {@code AnthropicAnomalyDetector} it replaced: the rule that flagged
 * the anomaly has already decided the outcome, this only narrates it, and a missing/unreachable
 * server degrades to the rule's own description rather than blocking anything.
 */
@Component
public class McpAnomalyDetector implements AnomalyDetector {

    private final McpToolClient mcpToolClient;

    public McpAnomalyDetector(McpToolClient mcpToolClient) {
        this.mcpToolClient = mcpToolClient;
    }

    @Override
    public AnomalyResult explain(AnomalyContext context) {
        return mcpToolClient.callTool("summarize_anomaly", Map.of("subject", context.subject(), "description", context.description()))
                .map(text -> new AnomalyResult(text, true))
                .orElseGet(() -> new AnomalyResult(context.description(), false));
    }
}
