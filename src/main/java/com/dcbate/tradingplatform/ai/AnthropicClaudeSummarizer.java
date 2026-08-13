package com.dcbate.tradingplatform.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Turns a plain-text payment-outcome description into a short, customer-facing summary via the
 * real Anthropic Messages API. Purely advisory: if the key is a placeholder or the call fails for
 * any reason, {@link #summarize} returns the raw context unchanged so the notification still
 * sends.
 */
@Slf4j
@Component
public class AnthropicClaudeSummarizer implements ClaudeSummarizer {

    private static final String PLACEHOLDER_KEY = "placeholder-set-me";
    private static final int MAX_TOKENS = 200;

    private final WebClient claudeWebClient;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public AnthropicClaudeSummarizer(
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
    public String summarize(String context) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals(PLACEHOLDER_KEY)) {
            log.debug("CLAUDE_API_KEY not configured; using the raw context as the notification summary");
            return context;
        }

        try {
            String text = callClaude(context);
            return text != null ? text : context;
        } catch (Exception e) {
            log.warn("Claude summarization failed, falling back to the raw context: {}", e.getMessage());
            return context;
        }
    }

    private String callClaude(String context) {
        String prompt = "In one or two short sentences, summarize this payment event for a customer-facing "
                + "notification: " + context;
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
