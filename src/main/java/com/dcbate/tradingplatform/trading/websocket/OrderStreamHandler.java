package com.dcbate.tradingplatform.trading.websocket;

import com.dcbate.tradingplatform.trading.api.dto.OrderResponse;
import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Broadcasts order status updates to every subscribed client (Low-Latency Pattern 5). Sends are
 * dispatched onto virtual threads so one slow/blocked session can never stall the publishing
 * caller or the other sessions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStreamHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final ExecutorService virtualThreadExecutor;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("WebSocket subscriber connected: sessionId={}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("WebSocket subscriber disconnected: sessionId={}, status={}", session.getId(), status);
    }

    public void publish(OrderResponse update) {
        if (sessions.isEmpty()) {
            return;
        }
        sessions.forEach(session -> virtualThreadExecutor.submit(() -> sendSafely(session, update)));
    }

    private void sendSafely(WebSocketSession session, OrderResponse update) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(update)));
            }
        } catch (IOException | JacksonException e) {
            log.warn("Failed to push order update to sessionId={}: {}", session.getId(), e.getMessage());
        }
    }
}
