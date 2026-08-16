package com.dcbate.tradingplatform.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts every {@code @KafkaListener} container once Kafka is actually reachable, instead of
 * during application startup — {@code KafkaConfig}'s listener factories both set
 * {@code autoStartup=false} specifically so a Kafka outage degrades to "consumers aren't running
 * yet" rather than failing the whole {@code ApplicationContext} refresh (which is what happened
 * before: a broker unreachable at that exact moment used to take the entire app down with it).
 *
 * <p>Deliberately checks/starts each {@link MessageListenerContainer} individually rather than
 * calling {@code KafkaListenerEndpointRegistry.start()} — the registry's own {@code isRunning()}
 * flips {@code true} as soon as its (harmless, skipped-because-autoStartup=false) startup pass
 * completes during context refresh, regardless of whether any container actually connected; a
 * registry-level check would see "already running" on the very first tick and never retry.
 *
 * <p>Same "retry on a schedule, never block anything else" shape as
 * {@code KafkaEventPublisher}'s fallback queue.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KafkaListenerStartupRunner {

    private final KafkaListenerEndpointRegistry registry;

    @Scheduled(fixedDelayString = "${kafka.listener-startup.retry-interval-ms:5000}")
    public void ensureListenersStarted() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isRunning()) {
                continue;
            }
            try {
                container.start();
                log.info("Kafka listener container started: {}", container.getListenerId());
            } catch (Exception e) {
                log.warn("Kafka listener container {} not started yet (Kafka may be unreachable), will retry: {}",
                        container.getListenerId(), e.getMessage());
            }
        }
    }
}
