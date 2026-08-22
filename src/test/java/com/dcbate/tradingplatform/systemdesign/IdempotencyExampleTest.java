package com.dcbate.tradingplatform.systemdesign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * Idempotency keys — the pattern {@code payment.service.PaymentServiceImpl.submit} uses for real
 * ({@code findByIdempotencyKey} before doing anything else). See
 * docs/TECH_STACK_INTERVIEW_GUIDE.md, "Idempotency."
 *
 * <p>This models the same idea with a plain in-memory map standing in for the payments table.
 */
class IdempotencyExampleTest {

    /** Executes an operation once per key; a repeated key returns the first result instead of re-running it. */
    static class IdempotentProcessor<T> {
        private final Map<String, T> resultsByKey = new ConcurrentHashMap<>();

        T process(String idempotencyKey, Function<String, T> operation) {
            return resultsByKey.computeIfAbsent(idempotencyKey, operation);
        }
    }

    @Test
    void aRetriedRequestWithTheSameKeyReturnsTheOriginalResultInsteadOfRunningAgain() {
        AtomicInteger timesActuallyExecuted = new AtomicInteger();
        IdempotentProcessor<String> processor = new IdempotentProcessor<>();

        String first = processor.process("key-abc", key -> {
            timesActuallyExecuted.incrementAndGet();
            return "payment-" + key + "-created";
        });
        // A client retrying after a lost response, same key — must not create a second payment.
        String retried = processor.process("key-abc", key -> {
            timesActuallyExecuted.incrementAndGet();
            return "payment-" + key + "-created";
        });

        assertThat(first).isEqualTo(retried);
        assertThat(timesActuallyExecuted.get()).isEqualTo(1);
    }

    @Test
    void differentKeysAreCompletelyIndependentOperations() {
        IdempotentProcessor<String> processor = new IdempotentProcessor<>();

        String resultA = processor.process("key-a", key -> "result-for-" + key);
        String resultB = processor.process("key-b", key -> "result-for-" + key);

        assertThat(resultA).isEqualTo("result-for-key-a");
        assertThat(resultB).isEqualTo("result-for-key-b");
    }
}
