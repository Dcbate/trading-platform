package com.dcbate.tradingplatform.systemdesign;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The saga pattern: a multi-step operation across things that can't share one database
 * transaction, with compensating actions to undo already-completed steps if a later one fails.
 * See docs/TECH_STACK_INTERVIEW_GUIDE.md, "The Saga pattern."
 *
 * <p>This is a deliberately generic, simplified version. {@code payment.service.SettlementServiceImpl}
 * is the real orchestrated saga this app actually runs for cross-bank payments (reserve → ledger
 * → clear → compensate) — same shape, real money instead of toy steps.
 */
class SagaPatternExampleTest {

    /** A step that does something, and knows how to undo it. */
    interface SagaStep {
        boolean execute();

        void compensate();
    }

    /** Orchestrated (not choreographed): one place decides the order and when to compensate. */
    static class OrderSaga {
        private final List<SagaStep> steps;

        OrderSaga(List<SagaStep> steps) {
            this.steps = steps;
        }

        boolean run() {
            List<SagaStep> completed = new ArrayList<>();
            for (SagaStep step : steps) {
                if (step.execute()) {
                    completed.add(step);
                } else {
                    // Undo everything that already succeeded, in reverse order — the newest
                    // change is compensated first, mirroring how you'd unwind a call stack.
                    for (int i = completed.size() - 1; i >= 0; i--) {
                        completed.get(i).compensate();
                    }
                    return false;
                }
            }
            return true;
        }
    }

    static class RecordingStep implements SagaStep {
        private final String name;
        private final boolean succeeds;
        private final List<String> log;

        RecordingStep(String name, boolean succeeds, List<String> log) {
            this.name = name;
            this.succeeds = succeeds;
            this.log = log;
        }

        @Override
        public boolean execute() {
            log.add("executed " + name);
            return succeeds;
        }

        @Override
        public void compensate() {
            log.add("compensated " + name);
        }
    }

    @Test
    void everyStepSucceedingNeedsNoCompensation() {
        List<String> log = new ArrayList<>();
        OrderSaga saga = new OrderSaga(List.of(
                new RecordingStep("reserveInventory", true, log),
                new RecordingStep("chargePayment", true, log),
                new RecordingStep("shipOrder", true, log)));

        boolean result = saga.run();

        assertThat(result).isTrue();
        assertThat(log).containsExactly("executed reserveInventory", "executed chargePayment", "executed shipOrder");
    }

    @Test
    void aFailedStepCompensatesEverythingThatAlreadySucceededInReverseOrder() {
        List<String> log = new ArrayList<>();
        OrderSaga saga = new OrderSaga(List.of(
                new RecordingStep("reserveInventory", true, log),
                new RecordingStep("chargePayment", false, log), // fails here
                new RecordingStep("shipOrder", true, log))); // never runs

        boolean result = saga.run();

        assertThat(result).isFalse();
        assertThat(log).containsExactly(
                "executed reserveInventory",
                "executed chargePayment",
                // shipOrder never executes — the saga stops at the first failure. Only
                // reserveInventory gets compensated, since it's the only step that had succeeded.
                "compensated reserveInventory");
    }
}
