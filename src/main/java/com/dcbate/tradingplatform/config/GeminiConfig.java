package com.dcbate.tradingplatform.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class GeminiConfig {

    @Bean
    public WebClient geminiWebClient(@Value("${gemini.api-url}") String apiUrl) {
        return WebClient.builder().baseUrl(apiUrl).build();
    }
}
