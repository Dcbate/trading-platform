package com.dcbate.tradingplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trading.matching-engine")
public record MatchingEngineProperties(int batchSize, long pollTimeoutMs, ThreadAffinity threadAffinity) {

    public record ThreadAffinity(boolean enabled, int core) {
    }
}
