package com.dcbate.batemcpserver.claude;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The one place this whole server talks to Anthropic. Each MCP tool builds its own prompt and
 * calls {@link #complete}; this class owns nothing tool-specific.
 *
 * <p>Deliberately does <b>not</b> swallow failures the way {@code AnthropicClaudeSummarizer} and
 * friends do in bate-banking-core — those live inside business logic that must never block on an
 * AI call, so they fall back to a rule-based string internally. This server has no business
 * context to write a sensible fallback sentence with; that context lives in whichever caller
 * invoked the tool. So a failure here throws, which Spring AI's {@code @McpTool} machinery turns
 * into an MCP tool error result — the caller decides what to do about it (bate-banking-core's own
 * fallback text, in its case), exactly the same "AI failure never blocks the real decision"
 * guarantee, just enforced one hop further out.
 */
@Slf4j
@Component
public class ClaudeClient {

    private static final String PLACEHOLDER_KEY = "placeholder-set-me";

    private final WebClient claudeWebClient;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public ClaudeClient(
            WebClient claudeWebClient,
            @Value("${claude.api-key}") String apiKey,
            @Value("${claude.model}") String model,
            @Value("${claude.timeout-ms}") long timeoutMs) {
        this.claudeWebClient = claudeWebClient;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    /**
     * @throws ClaudeUnavailableException if no key is configured, the call fails, or Claude
     *     returns no usable text — always with a message explaining which
     */
    public String complete(String prompt, int maxTokens) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals(PLACEHOLDER_KEY)) {
            throw new ClaudeUnavailableException("CLAUDE_API_KEY is not configured on bate-mcp-server");
        }

        var request = new ClaudeRequest(model, maxTokens, List.of(new ClaudeRequest.Message("user", prompt)));

        ClaudeResponse response;
        try {
            response = claudeWebClient
                    .post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(ClaudeResponse.class)
                    .block(timeout);
        } catch (Exception e) {
            log.warn("Claude call failed: {}", e.getMessage());
            throw new ClaudeUnavailableException("Claude API call failed: " + e.getMessage());
        }

        String text = extractText(response);
        if (text == null) {
            throw new ClaudeUnavailableException("Claude returned no text content");
        }
        return text;
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
