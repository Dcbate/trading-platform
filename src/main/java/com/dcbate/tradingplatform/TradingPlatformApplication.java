package com.dcbate.tradingplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. {@code @EnableScheduling} drives the Price Feed, Reconciliation, and
 * Kafka-fallback-queue schedulers; {@code @ConfigurationPropertiesScan} auto-registers every
 * {@code @ConfigurationProperties} record in the {@code config} package without needing to list
 * them individually.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class TradingPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingPlatformApplication.class, args);
    }
}
