package com.dcbate.tradingplatform.systemdesign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Event-driven architecture: a publisher doesn't call its subscribers directly, doesn't know how
 * many there are, and doesn't wait on any of them. See docs/TECH_STACK_INTERVIEW_GUIDE.md,
 * "Event-driven architecture."
 *
 * <p>Kafka — the real thing this app uses, see {@code config.KafkaConfig} and Part 6 of the
 * guide — is a durable, distributed, multi-process version of the same idea. This in-memory bus
 * demonstrates the core mechanism (decoupling one producer from N independent consumers) without
 * needing a broker running at all.
 */
class EventDrivenArchitectureExampleTest {

    /** A deliberately tiny stand-in for a Kafka topic: publish once, every subscriber gets it. */
    static class InMemoryEventBus<T> {
        private final List<Consumer<T>> subscribers = new ArrayList<>();

        void subscribe(Consumer<T> subscriber) {
            subscribers.add(subscriber);
        }

        /** The publisher has no idea who's listening, or how many — that's the whole point. */
        void publish(T event) {
            subscribers.forEach(subscriber -> subscriber.accept(event));
        }
    }

    record PaymentSubmitted(String paymentId) {
    }

    @Test
    void everySubscriberReceivesTheSameEventIndependently() {
        InMemoryEventBus<PaymentSubmitted> bus = new InMemoryEventBus<>();
        List<String> fraudServiceLog = new ArrayList<>();
        List<String> notificationServiceLog = new ArrayList<>();

        // Two completely independent "services" — neither knows the other exists.
        bus.subscribe(event -> fraudServiceLog.add("checked " + event.paymentId()));
        bus.subscribe(event -> notificationServiceLog.add("notified for " + event.paymentId()));

        bus.publish(new PaymentSubmitted("pay-1"));

        assertThat(fraudServiceLog).containsExactly("checked pay-1");
        assertThat(notificationServiceLog).containsExactly("notified for pay-1");
    }

    @Test
    void aSubscriberAddedLaterMissesEarlierEvents() {
        // Real Kafka topics retain history so a late-joining consumer group can catch up from
        // offset zero; this toy bus doesn't. A useful contrast, not an oversight — it makes
        // concrete that "event-driven" alone doesn't guarantee replayability, a real broker does.
        InMemoryEventBus<PaymentSubmitted> bus = new InMemoryEventBus<>();
        bus.publish(new PaymentSubmitted("pay-1"));

        List<String> lateSubscriberLog = new ArrayList<>();
        bus.subscribe(event -> lateSubscriberLog.add(event.paymentId()));
        bus.publish(new PaymentSubmitted("pay-2"));

        assertThat(lateSubscriberLog).containsExactly("pay-2");
    }
}
