package com.dcbate.tradingplatform.systemdesign;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Virtual threads (Java 21, <a href="https://openjdk.org/jeps/444">JEP 444</a>) — what
 * {@code config.VirtualThreadConfig}'s executor and {@code spring.threads.virtual.enabled} give
 * this app for real. See docs/TECH_STACK_INTERVIEW_GUIDE.md, "Java 21."
 *
 * <p>The point to see live: 10,000 concurrent "blocked on I/O" tasks complete quickly on virtual
 * threads — something a traditional platform-thread pool of the same size would either be unable
 * to create or would serialize through a small pool instead.
 */
class VirtualThreadsExampleTest {

    @Test
    void tenThousandConcurrentBlockingTasksCompleteQuicklyOnVirtualThreads() throws Exception {
        int taskCount = 10_000;
        AtomicInteger completed = new AtomicInteger();

        Instant start = Instant.now();
        try (ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, taskCount)
                    .mapToObj(i -> virtualThreadExecutor.submit(() -> {
                        // Simulates a blocking I/O wait (a database call, an HTTP request) — a
                        // virtual thread "parks" here without tying up a real OS thread, which is
                        // exactly why 10,000 of these can run concurrently instead of queueing.
                        Thread.sleep(Duration.ofMillis(20));
                        completed.incrementAndGet();
                        return null;
                    }))
                    .toList();
            for (Future<?> future : futures) {
                future.get();
            }
        }
        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(completed.get()).isEqualTo(taskCount);
        // Run one at a time, 10,000 x 20ms would take over 3 minutes. On virtual threads they run
        // concurrently and this finishes in roughly the time of ONE 20ms sleep, not ten thousand.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void everyTaskActuallyRunsOnItsOwnVirtualThread() throws Exception {
        int taskCount = 50;
        Set<Long> threadIds = ConcurrentHashMap.newKeySet();

        try (ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, taskCount)
                    .mapToObj(i -> virtualThreadExecutor.submit(() -> {
                        assertThat(Thread.currentThread().isVirtual()).isTrue();
                        threadIds.add(Thread.currentThread().threadId());
                        return null;
                    }))
                    .toList();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        // "newVirtualThreadPerTaskExecutor" is named literally that — one virtual thread per
        // submitted task, not a shared pool reusing a handful of threads across all of them.
        assertThat(threadIds).hasSize(taskCount);
    }
}
