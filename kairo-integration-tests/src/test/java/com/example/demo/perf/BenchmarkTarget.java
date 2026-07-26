package com.example.demo.perf;

/**
 * The target class the benchmark enhances and calls. It lives in the harness
 * test sources under a <em>weavable</em> package ({@code com.example.demo.perf}
 * is not on the Agent's {@code IgnorePolicy} list, unlike {@code com.example.kairo.*}),
 * so the <em>same</em> fixture class is used for both the V1.6.0 baseline and the
 * HEAD candidate runs (only the kairo implementation JARs differ). Methods are
 * deliberately small and allocation-free so the measured ns/op reflects dispatch
 * overhead rather than business logic.
 *
 * <p>{@link #scoreThrows(int)} throws a cached, stack-trace-free exception so the
 * THROWS scenario is not dominated by stack-trace capture cost (which would
 * mask dispatch overhead and is identical across builds anyway).
 */
public final class BenchmarkTarget {

    /** A single cached throwable with no stack trace, reused to avoid allocation in the hot loop. */
    private static final RuntimeException CHEAP_THROW =
            new RuntimeException("perf-throw") {
                @Override
                public Throwable fillInStackTrace() {
                    return this;
                }
            };

    public int score(int x) {
        return x * 2;
    }

    /** Always throws the cached exception; used by the THROWS scenario. */
    public int scoreThrows(int x) {
        throw CHEAP_THROW;
    }
}
