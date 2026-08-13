package com.dcbate.tradingplatform.ai;

import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Enriches an already-triggered threshold rule (price spike, order velocity, fraud signal, ...)
 * with a short AI-generated severity assessment via the real Gemini API. Never gates the
 * underlying decision: if the key is a placeholder or the call fails for any reason,
 * {@link #explain} degrades to the plain rule description so the calling flow is unaffected.
 */
@Slf4j
@Component
public class GeminiAnomalyDetector implements AnomalyDetector {

    private static final String PLACEHOLDER_KEY = "placeholder-set-me";

    private final WebClient geminiWebClient;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public GeminiAnomalyDetector(
            WebClient geminiWebClient,
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.model}") String model,
            @Value("${gemini.timeout-ms}") long timeoutMs) {
        this.geminiWebClient = geminiWebClient;
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = Duration.ofMillis(timeoutMs);
    }

    @Override
    public AnomalyResult explain(AnomalyContext context) {
        if (apiKey == null || apiKey.isBlank() || apiKey.equals(PLACEHOLDER_KEY)) {
            log.debug("GEMINI_API_KEY not configured; skipping AI enrichment for {}", context.subject());
            return new AnomalyResult(context.description(), false);
        }

        try {
            GeminiResponse response = callGemini(context);
            String text = extractText(response);
            return text != null ? new AnomalyResult(text, true) : new AnomalyResult(context.description(), false);
        } catch (Exception e) {
            log.warn("Gemini anomaly enrichment failed for {}, falling back to rule description: {}",
                    context.subject(), e.getMessage());
            return new AnomalyResult(context.description(), false);
        }
    }

    private GeminiResponse callGemini(AnomalyContext context) {
        String prompt = "In one short sentence, assess the severity of this anomaly and why it "
                + "matters: " + context.description();
        var request = new GeminiRequest(List.of(new GeminiRequest.Content(List.of(new GeminiRequest.Part(prompt)))));

        return geminiWebClient
                .post()
                .uri(uriBuilder -> uriBuilder
                        .path("/v1beta/models/{model}:generateContent")
                        .queryParam("key", apiKey)
                        .build(model))
                .bodyValue(request)
                .retrieve()
                .bodyToMono(GeminiResponse.class)
                .block(timeout);
    }

    private String extractText(GeminiResponse response) {
        if (response == null || response.candidates() == null || response.candidates().isEmpty()) {
            return null;
        }
        GeminiResponse.Content content = response.candidates().get(0).content();
        if (content == null || content.parts() == null || content.parts().isEmpty()) {
            return null;
        }
        return content.parts().get(0).text();
    }

    private record GeminiRequest(List<Content> contents) {
        private record Content(List<Part> parts) {
        }

        private record Part(String text) {
        }
    }

    private record GeminiResponse(List<Candidate> candidates) {
        private record Candidate(Content content) {
        }

        private record Content(List<Part> parts) {
        }

        private record Part(String text) {
        }
    }
}
