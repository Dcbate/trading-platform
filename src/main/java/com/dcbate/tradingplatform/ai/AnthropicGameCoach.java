package com.dcbate.tradingplatform.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Writes a Game Mode end-of-session debrief via the real Anthropic Messages API. Shares the
 * {@code claudeWebClient} bean and {@code claude.*} config with {@link AnthropicClaudeSummarizer}
 * — same provider, different prompt and a longer response, since a debrief is a short piece of
 * analysis rather than a one-line notification.
 */
@Slf4j
@Component
public class AnthropicGameCoach implements GameCoach {

    private static final String PLACEHOLDER_KEY = "placeholder-set-me";
    private static final int MAX_TOKENS = 400;

    private final WebClient claudeWebClient;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public AnthropicGameCoach(
            WebClient claudeWebClient,
            @Value("${claude.api-key}") String apiKey,
            @Value("${claude.model}") String model,
            @Value("${claude.timeout-ms}") long timeoutMs) {
        this.claudeWebClient = claudeWebClient;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public GameDebriefResult debrief(GameDebriefContext context) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals(PLACEHOLDER_KEY)) {
            log.debug("CLAUDE_API_KEY not configured; using the rule-based Game Mode debrief");
            return new GameDebriefResult(context.fallbackSummary(), false);
        }

        try {
            String text = callClaude(context.narrative());
            return text != null ? new GameDebriefResult(text, true) : new GameDebriefResult(context.fallbackSummary(), false);
        } catch (Exception e) {
            log.warn("Game Mode debrief generation failed, falling back to the rule-based summary: {}", e.getMessage());
            return new GameDebriefResult(context.fallbackSummary(), false);
        }
    }

    private String callClaude(String narrative) {
        String prompt = "You are a trading coach reviewing a finished session of a practice trading game "
                + "(fake money, no real stakes). Given the rules and full trade/loan history below, write a short "
                + "debrief (3-5 sentences, plain language, no headings or bullet points) explaining why the player "
                + "won or lost, which specific trades or loans helped the most, and which hurt the most. Reference "
                + "actual symbols and numbers from the history. Session:\n\n" + narrative;
        var request = new ClaudeRequest(model, MAX_TOKENS, List.of(new ClaudeRequest.Message("user", prompt)));

        ClaudeResponse response = claudeWebClient
                .post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ClaudeResponse.class)
                .block(timeout);

        return extractText(response);
    }

    private String extractText(ClaudeResponse response) {
        if (response == null || response.content() == null || response.content().isEmpty()) {
            return null;
        }
        return response.content().get(0).text();
    }

    private record ClaudeRequest(String model, @JsonProperty("max_tokens") int maxTokens, List<Message> messages) {
        private record Message(String role, String content) {
        }
    }

    private record ClaudeResponse(List<Content> content) {
        private record Content(String text) {
        }
    }
}
