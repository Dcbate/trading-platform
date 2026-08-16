package com.dcbate.batemcpserver.tools;

import com.dcbate.batemcpserver.claude.ClaudeClient;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Mirrors bate-banking-core's {@code AnthropicClaudeSummarizer} prompt exactly. The payment's
 * outcome (settled, failed, held for review) is already decided before this is called — this tool
 * only turns that outcome into a short, friendly customer-facing sentence.
 */
@Component
public class PaymentSummaryTool {

    private final ClaudeClient claudeClient;

    public PaymentSummaryTool(ClaudeClient claudeClient) {
        this.claudeClient = claudeClient;
    }

    @McpTool(name = "summarize_payment", description =
            "Turn a plain-text payment-outcome description into a short, friendly customer-facing "
                    + "notification summary (one or two sentences). The payment's real outcome has already "
                    + "been decided; this only writes the notification text.")
    public String summarizePayment(
            @McpToolParam(description = "Plain description of what happened, e.g. \"payment of £250.00 to Acme Ltd settled successfully\"", required = true)
            String context) {
        String prompt = "In one or two short sentences, summarize this payment event for a customer-facing "
                + "notification: " + context;
        return claudeClient.complete(prompt, 200);
    }
}
