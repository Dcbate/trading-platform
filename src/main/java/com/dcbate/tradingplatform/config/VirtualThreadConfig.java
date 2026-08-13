package com.dcbate.tradingplatform.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Virtual-thread executor (Low-Latency Pattern 1). Spring MVC request handling already runs on
 * virtual threads via {@code spring.threads.virtual.enabled=true}; this bean is the one used
 * explicitly for I/O-bound fan-out work outside the request thread, e.g. broadcasting WebSocket
 * order updates to many subscribers without dedicating a platform thread per connection.
 */
@Configuration
public class VirtualThreadConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
