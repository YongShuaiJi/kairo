package com.example.kairo.perf.soak;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic unit tests for {@link SoakBudgetChecker}: the two M2-D time-windowed failure
 * conditions (sustained resource-budget breach and persistent state drift) and the pure breach
 * computation against the baseline, including unsupported-metric sentinels. Uses a small-window
 * test budget so the threshold logic is exercised without feeding 5 minutes of observations.
 */
class SoakBudgetCheckerTest {

    private static final Instant T0 = Instant.parse("2026-07-31T00:00:00Z");

    /** A test budget with a 60s window (the logic is budget-agnostic; the documented window is 300s). */
    private static final SoakBudget TEST = new SoakBudget(15, 10, 2, 5, 60, 60);

    private static SoakObservation obs(int minute, long elapsedSec, long heap, long metaspace,
                                       int threads, long fd) {
        return new SoakObservation(minute, T0.plusSeconds(elapsedSec), elapsedSec, heap, metaspace,
                threads, fd, 1000, 0, 0, 0, 0, 0, 0L, 0, 0,
                false, 0L, false, false, false, false, false);
    }

    // -------------------------------------------------------- sustained breach

    @Test
    void sustainedHeapBreachFailsOnlyAfterTheWindow() {
        SoakBudgetChecker c = new SoakBudgetChecker(TEST);
        // Minute 1: baseline (heap 100MB). No baseline yet -> no breaches.
        assertThat(c.evaluate(obs(1, 0, 100_000_000, 10_000_000, 10, 50))).isNull();
        // Minute 2: heap +30% (over 15% budget) - breach begins, sustained 0s.
        assertThat(c.evaluate(obs(2, 60, 130_000_000, 10_000_000, 10, 50))).isNull();
        // Minute 3: still breached, sustained 60s (not > 60s).
        assertThat(c.evaluate(obs(3, 120, 130_000_000, 10_000_000, 10, 50))).isNull();
        // Minute 4: still breached, sustained 120s > 60s -> failure.
        SoakBudgetChecker.Failure f = c.evaluate(obs(4, 180, 130_000_000, 10_000_000, 10, 50));
        assertThat(f).isNotNull();
        assertThat(f.phase()).isEqualTo("sustained-resource-breach");
        assertThat(f.detail()).contains("heap");
    }

    @Test
    void transientHeapBreachThatRecoversDoesNotFail() {
        SoakBudgetChecker c = new SoakBudgetChecker(TEST);
        assertThat(c.evaluate(obs(1, 0, 100_000_000, 10_000_000, 10, 50))).isNull();   // baseline
        assertThat(c.evaluate(obs(2, 60, 130_000_000, 10_000_000, 10, 50))).isNull();  // breach
        assertThat(c.evaluate(obs(3, 120, 100_000_000, 10_000_000, 10, 50))).isNull(); // recovered
        assertThat(c.evaluate(obs(4, 180, 100_000_000, 10_000_000, 10, 50))).isNull(); // still fine
    }

    @Test
    void sustainedThreadBreachFailsAfterWindow() {
        SoakBudgetChecker c = new SoakBudgetChecker(TEST);
        assertThat(c.evaluate(obs(1, 0, 100, 10, 10, 50))).isNull();   // baseline threads=10
        assertThat(c.evaluate(obs(2, 60, 100, 10, 14, 50))).isNull(); // +4 > 2 breach
        assertThat(c.evaluate(obs(3, 120, 100, 10, 14, 50))).isNull(); // sustained 60s
        SoakBudgetChecker.Failure f = c.evaluate(obs(4, 180, 100, 10, 14, 50)); // sustained 120s
        assertThat(f).isNotNull();
        assertThat(f.detail()).contains("thread");
    }

    // -------------------------------------------------------- drift

    @Test
    void persistentDriftFailsAfterTheWindow() {
        SoakBudgetChecker c = new SoakBudgetChecker(TEST);
        // Drift begins at elapsed 60s.
        assertThat(c.evaluateDrift(true, 60, 60)).isNull();   // drift starts, persisted 0
        assertThat(c.evaluateDrift(true, 60, 120)).isNull();  // persisted 60s (not > 60)
        SoakBudgetChecker.Failure f = c.evaluateDrift(true, 60, 180); // persisted 120s > 60
        assertThat(f).isNotNull();
        assertThat(f.phase()).isEqualTo("persistent-state-drift");
    }

    @Test
    void transientDriftThatRecoversDoesNotFail() {
        SoakBudgetChecker c = new SoakBudgetChecker(TEST);
        assertThat(c.evaluateDrift(true, 60, 60)).isNull();    // drift
        assertThat(c.evaluateDrift(false, -1, 120)).isNull();  // recovered
        // Drift resumes: the prior transient must NOT count toward the new persistence window.
        assertThat(c.evaluateDrift(true, 180, 180)).isNull();   // starts fresh
        assertThat(c.evaluateDrift(true, 180, 240)).isNull();   // persisted 60s (not > 60)
        SoakBudgetChecker.Failure f = c.evaluateDrift(true, 180, 300); // persisted 120s
        assertThat(f).isNotNull();
    }

    // -------------------------------------------------------- pure breach computation

    @Test
    void breachesFlagEachResourceOverBudgetVsBaseline() {
        SoakObservation baseline = obs(1, 0, 100_000_000, 10_000_000, 10, 50);
        // heap +30% (breach), metaspace +5% (ok), threads +1 (ok), fd +1 (ok)
        SoakObservation o = obs(2, 60, 130_000_000, 10_500_000, 11, 51);
        SoakBudgetChecker.Breaches b = SoakBudgetChecker.breaches(o, baseline, TEST);
        assertThat(b.heap()).isTrue();
        assertThat(b.metaspace()).isFalse();
        assertThat(b.thread()).isFalse();
        assertThat(b.fd()).isFalse();
        assertThat(b.any()).isTrue();
    }

    @Test
    void fractionalGrowthAboveBudgetCannotBeRoundedDownIntoAPass() {
        SoakObservation baseline = obs(1, 0, 10_000, 10_000, 10, 50);
        SoakObservation o = obs(2, 60, 11_501, 11_001, 10, 50);
        SoakBudgetChecker.Breaches b = SoakBudgetChecker.breaches(o, baseline, TEST);
        assertThat(b.heap()).as("15.01% heap growth exceeds the 15% budget").isTrue();
        assertThat(b.metaspace()).as("10.01% metaspace growth exceeds the 10% budget").isTrue();
    }

    @Test
    void unsupportedMetricsNeverReportABreach() {
        SoakObservation baseline = obs(1, 0, 100, -1, 10, -1);  // metaspace/fd unsupported
        SoakObservation o = obs(2, 60, 130, -1, 14, -1);
        SoakBudgetChecker.Breaches b = SoakBudgetChecker.breaches(o, baseline, TEST);
        assertThat(b.metaspace()).isFalse(); // unsupported (-1) never fabricates a breach
        assertThat(b.fd()).isFalse();
        assertThat(b.heap()).isTrue();
        assertThat(b.thread()).isTrue();
    }

    @Test
    void noBreachesBeforeBaselineEstablished() {
        SoakBudgetChecker.Breaches b = SoakBudgetChecker.breaches(obs(1, 0, 999, 999, 999, 999), null, TEST);
        assertThat(b.any()).isFalse();
    }
}
