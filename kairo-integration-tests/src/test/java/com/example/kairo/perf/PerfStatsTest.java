package com.example.kairo.perf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PerfStatsTest {

    @Test
    void medianP95P99ForTenAscendingSamples() {
        // 1..10: median 5.5, p95 9.55, p99 9.91, mean 5.5
        double[] s = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        PerfStats ps = PerfStats.of(s, 1);

        assertThat(ps.median()).isCloseTo(5.5, within(1e-9));
        assertThat(ps.p95()).isCloseTo(9.55, within(1e-9));
        assertThat(ps.p99()).isCloseTo(9.91, within(1e-9));
        assertThat(ps.mean()).isCloseTo(5.5, within(1e-9));
        assertThat(ps.sampleCount()).isEqualTo(10);
        assertThat(ps.ops()).isEqualTo(1);
    }

    @Test
    void stddevAndDispersionForTenAscendingSamples() {
        double[] s = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        PerfStats ps = PerfStats.of(s, 1);
        // sample variance = 82.5/9 = 9.1666..., stddev = 3.027650...
        assertThat(ps.sampleStdDev()).isCloseTo(3.0276503540974917, within(1e-9));
        assertThat(ps.dispersion()).isCloseTo(3.0276503540974917 / 5.5, within(1e-9));
    }

    @Test
    void twoSamplesInterpolateCorrectly() {
        double[] s = {10, 20};
        PerfStats ps = PerfStats.of(s, 1);
        assertThat(ps.median()).isCloseTo(15.0, within(1e-9));
        assertThat(ps.p95()).isCloseTo(19.5, within(1e-9));
        assertThat(ps.p99()).isCloseTo(19.9, within(1e-9));
        assertThat(ps.sampleStdDev()).isCloseTo(Math.sqrt(50.0), within(1e-9));
    }

    @Test
    void singleSampleHasNoSpread() {
        PerfStats ps = PerfStats.of(new double[]{42.0}, 5);
        assertThat(ps.median()).isEqualTo(42.0);
        assertThat(ps.p95()).isEqualTo(42.0);
        assertThat(ps.p99()).isEqualTo(42.0);
        assertThat(ps.sampleStdDev()).isEqualTo(0.0);
        assertThat(ps.dispersion()).isEqualTo(0.0);
        assertThat(ps.ops()).isEqualTo(5);
    }

    @Test
    void inputArrayIsNotMutated() {
        double[] s = {5, 3, 1, 4, 2};
        double[] copy = s.clone();
        PerfStats.of(s, 1);
        assertThat(s).containsExactly(copy);
    }

    @Test
    void unsortedInputIsSortedInternally() {
        PerfStats ps = PerfStats.of(new double[]{10, 1, 5, 3, 7}, 1);
        assertThat(ps.median()).isCloseTo(5.0, within(1e-9));
        assertThat(ps.sortedSamples()).containsExactly(1.0, 3.0, 5.0, 7.0, 10.0);
    }

    @Test
    void rejectsEmptyInput() {
        assertThatThrownBy(() -> PerfStats.of(new double[0], 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PerfStats.of(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void percentileBoundsAreValidated() {
        PerfStats ps = PerfStats.of(new double[]{1.0, 2.0}, 1);
        assertThat(ps.percentile(0.0)).isEqualTo(1.0);
        assertThat(ps.percentile(100.0)).isEqualTo(2.0);
        assertThatThrownBy(() -> ps.percentile(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ps.percentile(101)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fixturesAreDeterministicAcrossRuns() {
        // Same fixture input must yield identical stats on every call (no randomness, no clock).
        double[] s = {7, 1, 9, 3, 5, 8, 2, 6, 4, 10};
        PerfStats a = PerfStats.of(s, 1);
        PerfStats b = PerfStats.of(s, 1);
        assertThat(a.median()).isEqualTo(b.median());
        assertThat(a.p95()).isEqualTo(b.p95());
        assertThat(a.p99()).isEqualTo(b.p99());
        assertThat(a.sampleStdDev()).isEqualTo(b.sampleStdDev());
    }
}
