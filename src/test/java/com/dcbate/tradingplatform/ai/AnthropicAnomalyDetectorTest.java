package com.dcbate.tradingplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AnthropicAnomalyDetectorTest {

    @Test
    void placeholderKeySkipsTheApiCallAndUsesTheRuleDescription() {
        AnthropicAnomalyDetector detector =
                new AnthropicAnomalyDetector(WebClient.builder().build(), "placeholder-set-me", "claude-3-5-haiku-20241022", 1000);

        AnomalyResult result = detector.explain(new AnomalyContext("subject", "rule fired"));

        assertThat(result.explanation()).isEqualTo("rule fired");
        assertThat(result.aiEnriched()).isFalse();
    }

    @Test
    void blankKeySkipsTheApiCall() {
        AnthropicAnomalyDetector detector = new AnthropicAnomalyDetector(WebClient.builder().build(), "  ", "claude-3-5-haiku-20241022", 1000);

        AnomalyResult result = detector.explain(new AnomalyContext("subject", "rule fired"));

        assertThat(result.aiEnriched()).isFalse();
    }

    @Test
    void unreachableApiFallsBackToTheRuleDescription() {
        WebClient webClient = WebClient.builder().baseUrl("http://127.0.0.1:1").build();
        AnthropicAnomalyDetector detector = new AnthropicAnomalyDetector(webClient, "a-real-looking-key", "claude-3-5-haiku-20241022", 1000);

        AnomalyResult result = detector.explain(new AnomalyContext("subject", "rule fired"));

        assertThat(result.explanation()).isEqualTo("rule fired");
        assertThat(result.aiEnriched()).isFalse();
    }
}
