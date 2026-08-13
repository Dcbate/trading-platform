package com.dcbate.tradingplatform.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class AnthropicClaudeSummarizerTest {

    @Test
    void placeholderKeySkipsTheApiCallAndReturnsRawContext() {
        AnthropicClaudeSummarizer summarizer =
                new AnthropicClaudeSummarizer(WebClient.builder().build(), "placeholder-set-me", "claude-3-5-haiku-20241022", 1000);

        assertThat(summarizer.summarize("payment failed")).isEqualTo("payment failed");
    }

    @Test
    void blankKeySkipsTheApiCall() {
        AnthropicClaudeSummarizer summarizer =
                new AnthropicClaudeSummarizer(WebClient.builder().build(), "  ", "claude-3-5-haiku-20241022", 1000);

        assertThat(summarizer.summarize("payment failed")).isEqualTo("payment failed");
    }

    @Test
    void unreachableApiFallsBackToRawContext() {
        WebClient webClient = WebClient.builder().baseUrl("http://127.0.0.1:1").build();
        AnthropicClaudeSummarizer summarizer =
                new AnthropicClaudeSummarizer(webClient, "a-real-looking-key", "claude-3-5-haiku-20241022", 1000);

        assertThat(summarizer.summarize("payment failed")).isEqualTo("payment failed");
    }
}
