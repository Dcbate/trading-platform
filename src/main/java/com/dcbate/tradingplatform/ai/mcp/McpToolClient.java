package com.dcbate.tradingplatform.ai.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Real MCP client for bate-mcp-server — every AI call in this app (fraud/anomaly severity,
 * payment summaries, Game Mode debriefs) goes through this one component, over the same
 * JSON-RPC/Streamable-HTTP protocol {@code bate-mcp-server} exposes (see its README for the
 * protocol details).
 *
 * <p>Connects lazily on the first call rather than at startup, so bate-mcp-server being briefly
 * unavailable never blocks this app's own startup or health checks — the AI features it backs are
 * advisory everywhere they're used (see {@code McpAnomalyDetector}, {@code McpClaudeSummarizer},
 * {@code McpGameCoach}), never load-bearing.
 */
@Slf4j
@Component
public class McpToolClient {

    private final Supplier<McpSyncClient> clientFactory;
    private volatile McpSyncClient client;

    public McpToolClient(@Value("${mcp.server.url}") String serverUrl, @Value("${mcp.client.timeout-ms}") long timeoutMs) {
        this(() -> newInitializedClient(serverUrl, Duration.ofMillis(timeoutMs)));
    }

    /** Package-private seam for tests — injects a fake client factory instead of a real network connection. */
    McpToolClient(Supplier<McpSyncClient> clientFactory) {
        this.clientFactory = clientFactory;
    }

    /**
     * Calls a tool by name and returns its text content, or {@link Optional#empty()} if the
     * server is unreachable, the tool call itself failed ({@code isError=true}), or the response
     * had no usable text. Never throws — callers decide their own fallback.
     */
    public Optional<String> callTool(String toolName, Map<String, Object> arguments) {
        try {
            McpSchema.CallToolResult result = connectedClient().callTool(new McpSchema.CallToolRequest(toolName, arguments));
            if (Boolean.TRUE.equals(result.isError())) {
                log.warn("MCP tool {} returned an error result: {}", toolName, firstText(result.content()));
                return Optional.empty();
            }
            return firstText(result.content());
        } catch (Exception e) {
            log.warn("MCP tool call to {} failed: {}", toolName, e.getMessage());
            discardBrokenClient();
            return Optional.empty();
        }
    }

    private Optional<String> firstText(List<McpSchema.Content> content) {
        return content == null ? Optional.empty() : content.stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .findFirst();
    }

    private McpSyncClient connectedClient() {
        McpSyncClient existing = client;
        if (existing != null) {
            return existing;
        }
        synchronized (this) {
            if (client == null) {
                client = clientFactory.get();
            }
            return client;
        }
    }

    private static McpSyncClient newInitializedClient(String serverUrl, Duration timeout) {
        McpClientTransport transport = HttpClientStreamableHttpTransport.builder(serverUrl).build();
        McpSyncClient newClient = McpClient.sync(transport).requestTimeout(timeout).build();
        newClient.initialize();
        log.info("Connected to MCP server at {}", serverUrl);
        return newClient;
    }

    /** A connection that just failed is worse than no connection — drop it so the next call reconnects fresh instead of retrying a broken session. */
    private synchronized void discardBrokenClient() {
        if (client != null) {
            client.closeGracefully();
            client = null;
        }
    }
}
