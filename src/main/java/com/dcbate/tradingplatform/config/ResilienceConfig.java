package com.dcbate.tradingplatform.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Circuit breakers for this app's two genuine external-dependency call sites:
 * {@code McpToolClient} (calls {@code bate-mcp-server}, a real network hop) and
 * {@code BankClearingClient} (stands in for a real bank clearing gateway — see its own javadoc).
 * Both are already wrapped in try/catch-and-fall-back at their call sites; a circuit breaker adds
 * the piece that alone doesn't give you: once a dependency is clearly unhealthy, stop spending
 * time and threads on calls that are almost certainly going to fail anyway, for a cooldown period,
 * instead of retrying every single request at full cost.
 *
 * <p>Deliberately wired by hand with the core {@code resilience4j-circuitbreaker} +
 * {@code resilience4j-micrometer} libraries rather than the {@code resilience4j-spring-boot3}
 * autoconfigured starter + {@code @CircuitBreaker} annotation — that starter targets Boot 3's
 * autoconfiguration contract, and this project has already found two real Boot-4-compatibility
 * gaps in starters that looked like they should just work (see {@link KafkaConfig} and
 * {@link TracingConfig}). Two plain {@code @Bean} definitions here cost little and remove that
 * risk entirely.
 */
@Slf4j
@Configuration
public class ResilienceConfig {

    private static final String MCP_CLIENT_BREAKER = "mcpClient";
    private static final String BANK_CLEARING_BREAKER = "bankClearing";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meterRegistry) {
        // Shared shape for both breakers: trip if >=50% of the last 10 calls failed, stay open
        // (refuse calls immediately) for 30s, then let 3 trial calls through before deciding
        // whether to fully close again or reopen. Only a thrown exception counts as a failure by
        // default — a call that returns normally, even with a "business" false/no result, is a
        // success as far as the breaker is concerned. That default is exactly right for both
        // breakers here: BankClearingClientImpl's deterministic "declined above threshold" is a
        // normal outcome, never a sign the gateway itself is unhealthy, and must never trip it.
        CircuitBreakerConfig sharedConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        // A single CircuitBreakerConfig argument becomes the registry's DEFAULT config, applied
        // to every breaker later created via the single-arg registry.circuitBreaker(name) — which
        // is what mcpClientCircuitBreaker()/bankClearingCircuitBreaker() below actually call.
        // (Caught live, not by inspection: an earlier version of this method passed
        // CircuitBreakerRegistry.of(Map.of(name, config, ...)) instead, which only registers
        // *named configs* for the separate two-arg circuitBreaker(name, configName) lookup — the
        // single-arg calls below silently fell back to resilience4j's own built-in defaults
        // (a 100-call sliding window) instead. Verified by killing bate-mcp-server: 20+ real
        // consecutive failures never tripped the breaker, and the buffered-failed-calls metric
        // read straight past the intended window size of 10, proving the wrong config was active.)
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(sharedConfig);

        // Exposes resilience4j_circuitbreaker_state, _calls, _failure_rate, etc. at
        // /actuator/prometheus, tagged by breaker name — the same Prometheus/Grafana stack every
        // other metric in this app already flows through (see OBSERVABILITY_PROOF.md).
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);

        // Breakers are created lazily, on the first registry.circuitBreaker(name) call — which
        // happens in the two @Bean methods below, AFTER this method returns. Attaching the
        // listener to registry.getAllCircuitBreakers() here would therefore attach to an empty
        // set (a second bug caught the same live run as the one above). onEntryAdded fires the
        // moment each breaker is actually created, regardless of bean-creation order, which is
        // the correct hook for this.
        registry.getEventPublisher().onEntryAdded(entryAddedEvent -> {
            CircuitBreaker breaker = entryAddedEvent.getAddedEntry();
            breaker.getEventPublisher().onStateTransition(event -> log.warn(
                    "Circuit breaker '{}' transitioned {} -> {}",
                    breaker.getName(), event.getStateTransition().getFromState(), event.getStateTransition().getToState()));
        });

        return registry;
    }

    @Bean
    public CircuitBreaker mcpClientCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(MCP_CLIENT_BREAKER);
    }

    @Bean
    public CircuitBreaker bankClearingCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(BANK_CLEARING_BREAKER);
    }
}
