package com.example.kairo.perf;

import java.util.Arrays;

/**
 * Pure, deterministic statistics over a set of timing samples. No wall-clock,
 * no I/O — every method is a function of its arguments so it can be unit-tested
 * with fixture values (see {@code PerfStatsTest}).
 *
 * <p>Samples are per-iteration timings in nanoseconds-per-operation. A lower
 * value is better. Percentiles use linear interpolation between closest ranks
 * (the common "type 7" convention), which is stable regardless of sample count.
 */
public final class PerfStats {

    private final double[] sorted;
    private final long ops;
    private final double mean;
    private final double sampleStdDev;

    private PerfStats(double[] sorted, long ops, double mean, double sampleStdDev) {
        this.sorted = sorted;
        this.ops = ops;
        this.mean = mean;
        this.sampleStdDev = sampleStdDev;
    }

    /** Compute stats from a non-empty sample array. The input is copied, not mutated. */
    public static PerfStats of(double[] samples, long ops) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("samples must be non-empty");
        }
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double sum = 0.0;
        for (double v : sorted) {
            sum += v;
        }
        double mean = sum / sorted.length;
        double sq = 0.0;
        for (double v : sorted) {
            double d = v - mean;
            sq += d * d;
        }
        double stddev = sorted.length > 1 ? Math.sqrt(sq / (sorted.length - 1)) : 0.0;
        return new PerfStats(sorted, ops, mean, stddev);
    }

    /** Median (P50), in the same units as the input samples. */
    public double median() {
        return percentile(50.0);
    }

    /**
     * Percentile in [0,100]. Linear interpolation between closest ranks:
     * index {@code p/100 * (n-1)}, fractional part interpolated.
     */
    public double percentile(double p) {
        if (p < 0.0 || p > 100.0) {
            throw new IllegalArgumentException("percentile out of range: " + p);
        }
        int n = sorted.length;
        if (n == 1) {
            return sorted[0];
        }
        double pos = (p / 100.0) * (n - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sorted[lo];
        }
        double frac = pos - lo;
        return sorted[lo] + frac * (sorted[hi] - sorted[lo]);
    }

    /** P95 convenience. */
    public double p95() {
        return percentile(95.0);
    }

    /** P99 convenience. */
    public double p99() {
        return percentile(99.0);
    }

    public double mean() {
        return mean;
    }

    /** Sample standard deviation (Bessel-corrected). */
    public double sampleStdDev() {
        return sampleStdDev;
    }

    /** Coefficient of variation = stddev/|mean|; a unitless dispersion/error measure. 0 when mean is 0. */
    public double dispersion() {
        return mean == 0.0 ? 0.0 : sampleStdDev / Math.abs(mean);
    }

    public int sampleCount() {
        return sorted.length;
    }

    public long ops() {
        return ops;
    }

    /** Raw sorted samples (defensive copy). */
    public double[] sortedSamples() {
        return sorted.clone();
    }
}
