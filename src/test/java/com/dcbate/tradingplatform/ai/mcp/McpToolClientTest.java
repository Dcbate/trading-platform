package com.dcbate.tradingplatform.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class McpToolClientTest {

    @Test
    void returnsTheTextContentOnASuccessfulToolCall() {
        McpSyncClient syncClient = mock(McpSyncClient.class);
        when(syncClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("your payment has settled")), false, null, null));
        McpToolClient toolClient = new McpToolClient(() -> syncClient);

        Optional<String> result = toolClient.callTool("summarize_payment", Map.of("context", "settled"));

        assertThat(result).contains("your payment has settled");
    }

    @Test
    void returnsEmptyWhenTheToolCallResultIsAnError() {
        McpSyncClient syncClient = mock(McpSyncClient.class);
        when(syncClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("CLAUDE_API_KEY is not configured")), true, null, null));
        McpToolClient toolClient = new McpToolClient(() -> syncClient);

        Optional<String> result = toolClient.callTool("summarize_payment", Map.of("context", "settled"));

        assertThat(result).isEmpty();
    }

    @Test
    void returnsEmptyWhenTheServerIsUnreachable() {
        McpSyncClient syncClient = mock(McpSyncClient.class);
        when(syncClient.callTool(any())).thenThrow(new RuntimeException("connection refused"));
        McpToolClient toolClient = new McpToolClient(() -> syncClient);

        Optional<String> result = toolClient.callTool("summarize_payment", Map.of("context", "settled"));

        assertThat(result).isEmpty();
    }

    @Test
    void reconnectsOnTheNextCallAfterAFailure() {
        McpSyncClient firstClient = mock(McpSyncClient.class);
        when(firstClient.callTool(any())).thenThrow(new RuntimeException("connection reset"));
        McpSyncClient secondClient = mock(McpSyncClient.class);
        when(secondClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("recovered")), false, null, null));

        Iterator<McpSyncClient> clients = List.of(firstClient, secondClient).iterator();
        McpToolClient toolClient = new McpToolClient(clients::next);

        assertThat(toolClient.callTool("summarize_payment", Map.of())).isEmpty();
        assertThat(toolClient.callTool("summarize_payment", Map.of())).contains("recovered");
    }

    @Test
    void reusesTheSameConnectionAcrossSuccessfulCalls() {
        McpSyncClient syncClient = mock(McpSyncClient.class);
        when(syncClient.callTool(any())).thenReturn(
                new McpSchema.CallToolResult(List.of(new McpSchema.TextContent("ok")), false, null, null));
        AtomicInteger factoryCalls = new AtomicInteger();
        McpToolClient toolClient = new McpToolClient(() -> {
            factoryCalls.incrementAndGet();
            return syncClient;
        });

        toolClient.callTool("summarize_payment", Map.of());
        toolClient.callTool("summarize_payment", Map.of());

        assertThat(factoryCalls.get()).isEqualTo(1);
        verify(syncClient, times(2)).callTool(any());
    }

    @Test
    void skipsTheCallEntirelyAndReturnsEmptyWhenTheCircuitBreakerIsOpen() {
        McpSyncClient syncClient = mock(McpSyncClient.class);
        CircuitBreaker breaker = CircuitBreaker.ofDefaults("forced-open");
        breaker.transitionToOpenState();
        McpToolClient toolClient = new McpToolClient(() -> syncClient, breaker);

        Optional<String> result = toolClient.callTool("summarize_payment", Map.of());

        assertThat(result).isEmpty();
        // The whole point of an open breaker: we never even attempt the call, so the client
        // (and, in production, the network) is never touched.
        verifyNoInteractions(syncClient);
    }

    @Test
    void tripsOpenAfterRepeatedFailuresAndThenSkipsFurtherCalls() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .build();
        CircuitBreaker breaker = CircuitBreaker.of("test-tripping", config);

        McpSyncClient failingClient = mock(McpSyncClient.class);
        when(failingClient.callTool(any())).thenThrow(new RuntimeException("connection refused"));
        McpToolClient toolClient = new McpToolClient(() -> failingClient, breaker);

        // 4 real failures, 100% failure rate over a 4-call window -> the breaker trips itself.
        for (int i = 0; i < 4; i++) {
            assertThat(toolClient.callTool("summarize_payment", Map.of())).isEmpty();
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // A 5th call is skipped by the breaker before ever reaching the (still-failing) client.
        clearInvocations(failingClient);
        assertThat(toolClient.callTool("summarize_payment", Map.of())).isEmpty();
        verifyNoInteractions(failingClient);
    }
}
