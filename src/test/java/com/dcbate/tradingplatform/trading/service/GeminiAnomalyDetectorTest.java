package com.dcbate.tradingplatform.trading.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class GeminiAnomalyDetectorTest {

    @Test
    void placeholderKeySkipsTheApiCallAndUsesTheRuleDescription() {
        GeminiAnomalyDetector detector =
                new GeminiAnomalyDetector(WebClient.builder().build(), "placeholder-set-me", "gemini-2.0-flash", 1000);

        AnomalyResult result = detector.explain(new AnomalyContext("subject", "rule fired"));

        assertThat(result.explanation()).isEqualTo("rule fired");
        assertThat(result.aiEnriched()).isFalse();
    }

    @Test
    void blankKeySkipsTheApiCall() {
        GeminiAnomalyDetector detector = new GeminiAnomalyDetector(WebClient.builder().build(), "  ", "gemini-2.0-flash", 1000);

        AnomalyResult result = detector.explain(new AnomalyContext("subject", "rule fired"));

        assertThat(result.aiEnriched()).isFalse();
    }

    @Test
    void unreachableApiFallsBackToTheRuleDescription() {
        WebClient webClient = WebClient.builder().baseUrl("http://127.0.0.1:1").build();
        GeminiAnomalyDetector detector = new GeminiAnomalyDetector(webClient, "a-real-looking-key", "gemini-2.0-flash", 1000);

        AnomalyResult result = detector.explain(new AnomalyContext("subject", "rule fired"));

        assertThat(result.explanation()).isEqualTo("rule fired");
        assertThat(result.aiEnriched()).isFalse();
    }
}
