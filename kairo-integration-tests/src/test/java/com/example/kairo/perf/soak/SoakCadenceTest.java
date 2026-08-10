package com.example.kairo.perf.soak;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the fixed M2-D cadence (&sect;9.4) and resource budgets are explicit and equal the
 * documented values verbatim. The production {@link SoakClock.WallClock} default and these
 * fixed constants must remain unchanged by test-only clock/cadence injection (M2-D brief).
 */
class SoakCadenceTest {

    @Test
    void cadenceIsTheFixedDocumentedM2dCadence() {
        SoakCadence c = SoakCadence.DOCUMENTED;
        assertThat(c.summaryInterval()).isEqualTo(Duration.ofMinutes(1));
        assertThat(c.batchInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(c.disconnectInterval()).isEqualTo(Duration.ofMinutes(30));
        // §9.4 explicit values, in seconds, so a drift in units would be caught.
        assertThat(c.summaryInterval().toSeconds()).isEqualTo(60);
        assertThat(c.batchInterval().toSeconds()).isEqualTo(300);
        assertThat(c.disconnectInterval().toSeconds()).isEqualTo(1800);
    }

    @Test
    void cadenceIntervalsAreOrderedAndNonTrivial() {
        SoakCadence c = SoakCadence.DOCUMENTED;
        assertThat(c.summaryInterval()).isLessThan(c.batchInterval());
        assertThat(c.batchInterval()).isLessThan(c.disconnectInterval());
        assertThat(c.disconnectInterval()).isGreaterThan(Duration.ZERO);
    }

    @Test
    void budgetsReuseM2cResourceThresholdsAndM2dStabilityWindows() {
        SoakBudget b = SoakBudget.DOCUMENTED;
        // §9.3 resource thresholds (reused, not weakened).
        assertThat(b.maxHeapGrowthPct()).isEqualTo(15);
        assertThat(b.maxMetaspaceGrowthPct()).isEqualTo(10);
        assertThat(b.maxThreadDelta()).isEqualTo(2);
        assertThat(b.maxFdDelta()).isEqualTo(5);
        // §9.4 stability windows: drift / sustained breach must persist > 5 minutes to fail.
        assertThat(b.driftThresholdSeconds()).isEqualTo(300);
        assertThat(b.sustainedBreachWindowSeconds()).isEqualTo(300);
    }

    @Test
    void wallClockIsTheProductionDefaultAndDoesNotAdvanceSynthetically() {
        // The production clock advances real time only; tick() is a no-op. The accelerated
        // clock is a test seam, never the default.
        SoakClock wall = new SoakClock.WallClock();
        long before = wall.now().toEpochMilli();
        Duration elapsedBefore = wall.elapsed();
        wall.tick(); // must not throw and must not synthetically advance
        long after = wall.now().toEpochMilli();
        // Real time may have advanced, but never backward; tick() added no virtual jump.
        assertThat(after).isGreaterThanOrEqualTo(before);
        assertThat(wall.elapsed()).isGreaterThanOrEqualTo(elapsedBefore);
    }

    @Test
    void acceleratedClockAdvancesVirtualTimeByStepPerTick() {
        Instant start = Instant.parse("2026-07-31T00:00:00Z");
        SoakClock accelerated = new SoakClock.AcceleratedClock(start, Duration.ofMinutes(1));
        assertThat(accelerated.now()).isEqualTo(start);
        assertThat(accelerated.elapsed()).isZero();
        accelerated.tick();
        assertThat(accelerated.now()).isEqualTo(start.plus(Duration.ofMinutes(1)));
        assertThat(accelerated.elapsed()).isEqualTo(Duration.ofMinutes(1));
        accelerated.tick();
        assertThat(accelerated.now()).isEqualTo(start.plus(Duration.ofMinutes(2)));
        assertThat(accelerated.elapsed()).isEqualTo(Duration.ofMinutes(2));
        accelerated.reset();
        assertThat(accelerated.elapsed()).as("warm-up time is excluded from measurement").isZero();
        assertThat(accelerated.now()).as("reset never moves the evidence clock backward")
                .isEqualTo(start.plus(Duration.ofMinutes(2)));
    }

    @Test
    void acceleratedClockRejectsNonAdvancingSteps() {
        Instant start = Instant.parse("2026-07-31T00:00:00Z");
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new SoakClock.AcceleratedClock(start, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }
}
