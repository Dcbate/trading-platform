package com.dcbate.tradingplatform.systemdesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The circuit breaker pattern, using the real Resilience4j library this app wires up for real in
 * {@code config.ResilienceConfig} (for {@code McpToolClient} and {@code BankClearingClientImpl}).
 * See docs/TECH_STACK_INTERVIEW_GUIDE.md, "The Circuit Breaker pattern."
 *
 * <p>Here the exact same library is pointed at a toy "flaky service" instead of a real network
 * call, so the whole CLOSED → OPEN → HALF_OPEN → CLOSED lifecycle can be driven and asserted on
 * one thread, with only a single short real sleep, instead of needing a container to kill.
 */
class CircuitBreakerExampleTest {

    /** A dependency that fails on command, so a test can drive it deterministically. */
    static class FlakyService {
        private final AtomicInteger callCount = new AtomicInteger();
        private volatile boolean healthy = true;

        String call() {
            callCount.incrementAndGet();
            if (!healthy) {
                throw new RuntimeException("service is down");
            }
            return "ok";
        }

        void breakIt() {
            healthy = false;
        }

        void fixIt() {
            healthy = true;
        }

        int callCount() {
            return callCount.get();
        }
    }

    private static CircuitBreaker newBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(4)
                .minimumNumberOfCalls(4)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofMillis(20))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
        return CircuitBreaker.of("flaky-service", config);
    }

    @Test
    void staysClosedAndKeepsCallingWhileTheServiceIsHealthy() {
        FlakyService service = new FlakyService();
        CircuitBreaker breaker = newBreaker();

        for (int i = 0; i < 4; i++) {
            assertThat(breaker.executeSupplier(service::call)).isEqualTo("ok");
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(service.callCount()).isEqualTo(4);
    }

    @Test
    void tripsOpenAfterEnoughFailuresAndThenStopsCallingTheServiceAtAll() {
        FlakyService service = new FlakyService();
        service.breakIt();
        CircuitBreaker breaker = newBreaker();

        // 4 real failures, 100% failure rate over the 4-call window -> the breaker trips itself.
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breaker.executeSupplier(service::call)).isInstanceOf(RuntimeException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int callsBeforeSkip = service.callCount();
        // The breaker itself refuses the 5th call with its own exception type — the service is
        // never touched again, which is the entire point of tripping in the first place.
        assertThatThrownBy(() -> breaker.executeSupplier(service::call)).isInstanceOf(CallNotPermittedException.class);
        assertThat(service.callCount()).isEqualTo(callsBeforeSkip);
    }

    @Test
    void recoversToClosedOnceTheServiceIsHealthyAgainAfterTheCooldown() throws InterruptedException {
        FlakyService service = new FlakyService();
        service.breakIt();
        CircuitBreaker breaker = newBreaker();

        for (int i = 0; i < 4; i++) {
            try {
                breaker.executeSupplier(service::call);
            } catch (RuntimeException expected) {
                // tripping the breaker is the point of this loop, not an assertion target
            }
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        service.fixIt();
        Thread.sleep(30); // just past the 20ms waitDurationInOpenState configured above

        // The first call after the cooldown is a HALF_OPEN trial, not an automatic pass — it's
        // this succeeding that actually flips the breaker back, not the clock alone.
        assertThat(breaker.executeSupplier(service::call)).isEqualTo("ok");
        assertThat(breaker.executeSupplier(service::call)).isEqualTo("ok");

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }
}
