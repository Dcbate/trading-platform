package com.dcbate.tradingplatform.systemdesign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Retries with exponential backoff — the pattern behind
 * {@code notification.event.NotificationEventConsumer}'s real {@code @RetryableTopic} (1s, 2s,
 * 4s, 8s, 16s, six attempts, then a dead-letter topic). See docs/TECH_STACK_INTERVIEW_GUIDE.md,
 * "Retries & backoff."
 *
 * <p>This is a minimal hand-rolled version of the same idea with millisecond delays, so the whole
 * retry-then-give-up sequence runs in well under a second instead of the real ~31-second worst
 * case.
 */
class RetryWithBackoffExampleTest {

    /** Retries up to maxAttempts times, doubling the delay each time, then lets the failure through. */
    static <T> T retryWithExponentialBackoff(Callable<T> action, int maxAttempts, long initialDelayMs, List<Long> delaysUsed) throws Exception {
        long delay = initialDelayMs;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.call();
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    throw e; // out of attempts — the same moment a real pipeline lands on a DLQ
                }
                delaysUsed.add(delay);
                Thread.sleep(delay);
                delay *= 2;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    @Test
    void succeedsOnAnEarlierAttemptWithoutExhaustingAllRetries() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> delaysUsed = new ArrayList<>();

        String result = retryWithExponentialBackoff(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("transient failure");
            }
            return "success";
        }, 5, 5, delaysUsed);

        assertThat(result).isEqualTo("success");
        assertThat(attempts.get()).isEqualTo(3);
        // Two failures happened before the third attempt succeeded, so only two delays were used.
        assertThat(delaysUsed).containsExactly(5L, 10L);
    }

    @Test
    void eachRetryWaitsLongerThanTheLastBeforeEventuallyGivingUp() {
        List<Long> delaysUsed = new ArrayList<>();

        assertThatThrownBy(() -> retryWithExponentialBackoff(() -> {
            throw new RuntimeException("permanently broken");
        }, 4, 5, delaysUsed))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("permanently broken");

        // 4 attempts total means 3 waits in between, each double the last — the same shape as
        // NotificationEventConsumer's real 1s/2s/4s/8s/16s, just compressed to milliseconds here.
        assertThat(delaysUsed).containsExactly(5L, 10L, 20L);
    }
}
