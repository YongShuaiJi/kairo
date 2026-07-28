package com.example.kairo.perf.statecycle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused deterministic tests for {@link StateCycleHarness.ScenarioAccumulator}
 * (defect 2): the single concurrent cycle must be recorded as completed without a
 * per-cycle sample (its evidence lives in the {@code concurrentConflict} block), and
 * a failure must increment the failed count.
 */
class StateCycleScenarioAccumulatorTest {

    @Test
    void recordConcurrentIncrementsCompletedWithoutSample() {
        StateCycleHarness.ScenarioAccumulator acc = new StateCycleHarness.ScenarioAccumulator();
        acc.recordConcurrent();
        assertThat(acc.completed).as("completed").isEqualTo(1);
        assertThat(acc.failed).as("failed").isZero();
        assertThat(acc.hasSample()).as("no per-cycle sample for concurrent").isFalse();
    }

    @Test
    void failIncrementsFailedCount() {
        StateCycleHarness.ScenarioAccumulator acc = new StateCycleHarness.ScenarioAccumulator();
        acc.fail();
        assertThat(acc.failed).as("failed").isEqualTo(1);
        assertThat(acc.completed).as("completed").isZero();
        assertThat(acc.hasSample()).as("no sample on failure").isFalse();
    }
}
