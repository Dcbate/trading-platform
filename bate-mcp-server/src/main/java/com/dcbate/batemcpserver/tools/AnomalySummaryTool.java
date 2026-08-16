package com.dcbate.batemcpserver.tools;

import com.dcbate.batemcpserver.claude.ClaudeClient;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Mirrors bate-banking-core's {@code AnthropicAnomalyDetector} prompt exactly, as an MCP tool
 * instead of an in-process call. A rule (price spike, order velocity, fraud signal) has already
 * fired before this is ever invoked — this tool only writes the "why it matters" narration.
 */
@Component
public class AnomalySummaryTool {

    private final ClaudeClient claudeClient;

    public AnomalySummaryTool(ClaudeClient claudeClient) {
        this.claudeClient = claudeClient;
    }

    @McpTool(name = "summarize_anomaly", description =
            "Write a one-sentence severity assessment for an already-flagged anomaly (a price spike, "
                    + "unusual order velocity, a fraud rule). The anomaly has already been acted on; this "
                    + "only produces the human-readable explanation for the record and notification text.")
    public String summarizeAnomaly(
            @McpToolParam(description = "What triggered the rule, e.g. \"EUR/USD price\" or \"client-42 order velocity\"", required = true)
            String subject,
            @McpToolParam(description = "The rule's own plain-text description of what it found, e.g. \"price moved 18% in one tick, threshold is 15%\"", required = true)
            String description) {
        String prompt = "In one short sentence, assess the severity of this anomaly and why it matters: "
                + subject + " — " + description;
        return claudeClient.complete(prompt, 100);
    }
}
