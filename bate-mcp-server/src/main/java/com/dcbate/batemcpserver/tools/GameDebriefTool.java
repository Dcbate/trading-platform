package com.dcbate.batemcpserver.tools;

import com.dcbate.batemcpserver.claude.ClaudeClient;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * Mirrors bate-banking-core's {@code AnthropicGameCoach} prompt exactly. The session's win/loss
 * outcome is already decided by {@code GameServiceImpl.evaluate} before this is called — this
 * tool only writes the coaching debrief explaining it.
 */
@Component
public class GameDebriefTool {

    private final ClaudeClient claudeClient;

    public GameDebriefTool(ClaudeClient claudeClient) {
        this.claudeClient = claudeClient;
    }

    @McpTool(name = "debrief_game_session", description =
            "Write a short (3-5 sentence) coaching debrief for a finished Game Mode trading session, "
                    + "explaining why the player won or lost and which specific trades or loans helped or "
                    + "hurt the most, given the difficulty's rules and the full trade/loan history.")
    public String debriefGameSession(
            @McpToolParam(description =
                    "The full session narrative: difficulty rules, outcome, every trade and loan in order, "
                            + "and final per-symbol P&L", required = true)
            String narrative) {
        String prompt = "You are a trading coach reviewing a finished session of a practice trading game "
                + "(fake money, no real stakes). Given the rules and full trade/loan history below, write a short "
                + "debrief (3-5 sentences, plain language, no headings or bullet points) explaining why the player "
                + "won or lost, which specific trades or loans helped the most, and which hurt the most. Reference "
                + "actual symbols and numbers from the history. Session:\n\n" + narrative;
        return claudeClient.complete(prompt, 400);
    }
}
