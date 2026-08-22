package com.dcbate.tradingplatform.ai.mcp;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
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
import org.springframework.beans.factory.annotation.Autowired;
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
 * advisory everywhere they're used (see {@code AnomalyDetectorImpl}, {@code ClaudeSummarizerImpl},
 * {@code GameCoachImpl}), never load-bearing.
 */
@Slf4j
@Component
public class McpToolClient {

    private final Supplier<McpSyncClient> clientFactory;
    private final CircuitBreaker circuitBreaker;
    private volatile McpSyncClient client;

    @Autowired
    public McpToolClient(
            @Value("${mcp.server.url}") String serverUrl,
            @Value("${mcp.client.timeout-ms}") long timeoutMs,
            CircuitBreaker mcpClientCircuitBreaker) {
        this(() -> newInitializedClient(serverUrl, Duration.ofMillis(timeoutMs)), mcpClientCircuitBreaker);
    }

    /**
     * Package-private seam for tests that don't care about the breaker — injects a fake client
     * factory and a permissive, always-closed breaker of its own (never shared with the real one).
     */
    McpToolClient(Supplier<McpSyncClient> clientFactory) {
        this(clientFactory, CircuitBreaker.ofDefaults("test-mcp-client"));
    }

    /** Package-private seam for tests that specifically exercise breaker behavior (open/half-open/closed). */
    McpToolClient(Supplier<McpSyncClient> clientFactory, CircuitBreaker circuitBreaker) {
        this.clientFactory = clientFactory;
        this.circuitBreaker = circuitBreaker;
    }

    /**
     * Calls a tool by name and returns its text content, or {@link Optional#empty()} if the
     * server is unreachable, the tool call itself failed ({@code isError=true}), the circuit
     * breaker is currently open (bate-mcp-server has been failing enough that we're not even
     * attempting the call right now), or the response had no usable text. Never throws — callers
     * decide their own fallback.
     */
    public Optional<String> callTool(String toolName, Map<String, Object> arguments) {
        try {
            McpSchema.CallToolResult result = circuitBreaker.executeSupplier(
                    () -> connectedClient().callTool(new McpSchema.CallToolRequest(toolName, arguments)));
            if (Boolean.TRUE.equals(result.isError())) {
                log.warn("MCP tool {} returned an error result: {}", toolName, firstText(result.content()));
                return Optional.empty();
            }
            return firstText(result.content());
        } catch (CallNotPermittedException e) {
            // The breaker itself refused the call — no client interaction happened at all, so
            // there's nothing broken to discard, unlike the real-failure branch below.
            log.warn("MCP tool call to {} skipped — circuit breaker '{}' is open", toolName, e.getCausingCircuitBreakerName());
            return Optional.empty();
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
