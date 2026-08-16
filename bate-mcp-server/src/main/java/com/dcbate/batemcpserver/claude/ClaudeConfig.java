package com.dcbate.batemcpserver.claude;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** WebClient for {@link ClaudeClient}, pointed at the real Anthropic Messages API. */
@Configuration
public class ClaudeConfig {

    @Bean
    public WebClient claudeWebClient(@Value("${claude.api-url}") String apiUrl) {
        return WebClient.builder().baseUrl(apiUrl).build();
    }
}
