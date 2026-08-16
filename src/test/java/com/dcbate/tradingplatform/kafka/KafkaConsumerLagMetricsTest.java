package com.dcbate.tradingplatform.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class KafkaConsumerLagMetricsTest {

    @Test
    void constructionNeverThrowsEvenWithAnUnresolvableBootstrapServer() {
        // AdminClient.create() eagerly resolves bootstrap.servers and throws if DNS can't resolve
        // it — the constructor must not call it directly, or a Kafka outage at app-startup time
        // would fail the whole Spring context instead of just degrading this one gauge.
        assertThatCode(() -> new KafkaConsumerLagMetrics("unresolvable-host:29092", new SimpleMeterRegistry()))
                .doesNotThrowAnyException();
    }

    @Test
    void refreshDegradesGracefullyWhenKafkaIsUnreachable() {
        KafkaConsumerLagMetrics metrics = new KafkaConsumerLagMetrics("unresolvable-host:29092", new SimpleMeterRegistry());

        assertThatCode(metrics::refresh).doesNotThrowAnyException();
    }
}
