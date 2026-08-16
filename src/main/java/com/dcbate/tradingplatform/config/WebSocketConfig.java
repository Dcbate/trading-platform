package com.dcbate.tradingplatform.config;

import com.dcbate.tradingplatform.trading.websocket.OrderStreamHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers {@code OrderStreamHandler} at {@code /v1/orders/stream}, the live order-status feed. */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final OrderStreamHandler orderStreamHandler;

    @Value("${app.security.allowed-origins}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(orderStreamHandler, "/v1/orders/stream").setAllowedOrigins(allowedOrigins.split(","));
    }
}
