package com.dcbate.tradingplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code trading.matching-engine.*}, read by {@code MatchingEngineConsumerRunner}.
 * {@code threadAffinity.enabled} defaults to {@code false} — see that class's javadoc for why.
 */
@ConfigurationProperties(prefix = "trading.matching-engine")
public record MatchingEngineProperties(int batchSize, long pollTimeoutMs, ThreadAffinity threadAffinity) {

    public record ThreadAffinity(boolean enabled, int core) {
    }
}
