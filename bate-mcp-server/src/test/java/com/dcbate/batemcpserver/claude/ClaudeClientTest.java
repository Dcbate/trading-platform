package com.dcbate.batemcpserver.claude;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class ClaudeClientTest {

    @Test
    void placeholderKeyThrowsWithoutCallingTheApi() {
        ClaudeClient client = new ClaudeClient(WebClient.builder().build(), "placeholder-set-me", "claude-3-5-haiku-20241022", 1000);

        assertThatThrownBy(() -> client.complete("prompt", 100))
                .isInstanceOf(ClaudeUnavailableException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    void blankKeyThrows() {
        ClaudeClient client = new ClaudeClient(WebClient.builder().build(), "  ", "claude-3-5-haiku-20241022", 1000);

        assertThatThrownBy(() -> client.complete("prompt", 100)).isInstanceOf(ClaudeUnavailableException.class);
    }

    @Test
    void unreachableApiThrows() {
        WebClient webClient = WebClient.builder().baseUrl("http://127.0.0.1:1").build();
        ClaudeClient client = new ClaudeClient(webClient, "a-real-looking-key", "claude-3-5-haiku-20241022", 1000);

        assertThatThrownBy(() -> client.complete("prompt", 100))
                .isInstanceOf(ClaudeUnavailableException.class)
                .hasMessageContaining("Claude API call failed");
    }
}
