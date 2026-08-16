package com.dcbate.tradingplatform.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Enriches an already-triggered threshold rule (price spike, order velocity, fraud signal, ...)
 * with a short AI-generated severity assessment via the real Anthropic Messages API. Never gates
 * the underlying decision: if the key is a placeholder or the call fails for any reason,
 * {@link #explain} degrades to the plain rule description so the calling flow is unaffected.
 *
 * <p>Shares the {@code claudeWebClient} bean and {@code claude.*} config with
 * {@link AnthropicClaudeSummarizer} and {@link AnthropicGameCoach} — every AI call in this app
 * goes through Anthropic's API now, rather than splitting AI usage across two providers.
 */
@Slf4j
@Component
public class AnthropicAnomalyDetector implements AnomalyDetector {

    private static final String PLACEHOLDER_KEY = "placeholder-set-me";
    private static final int MAX_TOKENS = 100;

    private final WebClient claudeWebClient;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public AnthropicAnomalyDetector(
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
    public AnomalyResult explain(AnomalyContext context) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals(PLACEHOLDER_KEY)) {
            log.debug("CLAUDE_API_KEY not configured; skipping AI enrichment for {}", context.subject());
            return new AnomalyResult(context.description(), false);
        }

        try {
            String text = callClaude(context);
            return text != null ? new AnomalyResult(text, true) : new AnomalyResult(context.description(), false);
        } catch (Exception e) {
            log.warn("Claude anomaly enrichment failed for {}, falling back to rule description: {}",
                    context.subject(), e.getMessage());
            return new AnomalyResult(context.description(), false);
        }
    }

    private String callClaude(AnomalyContext context) {
        String prompt = "In one short sentence, assess the severity of this anomaly and why it "
                + "matters: " + context.description();
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
