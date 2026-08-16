package com.dcbate.tradingplatform.ai.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
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
}
