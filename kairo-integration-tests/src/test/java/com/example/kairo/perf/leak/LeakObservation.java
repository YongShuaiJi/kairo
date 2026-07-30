package com.example.kairo.perf.leak;

import java.time.Instant;

/**
 * One resource-observation window captured by {@link ResourceProbe}. A window is a
 * point-in-time snapshot taken after an explicit full-GC attempt, so heap/metaspace
 * reflect reclaimable memory and the ClassLoader weak-reference set reflects what has
 * actually been collected.
 *
 * <p>The {@code label} identifies the window's position in the run
 * ({@code baseline}, {@code post-cycles}, {@code post-close}, or an intermediate
 * {@code after-<scenario>}). The baseline window is captured <em>after warm-up</em> so
 * one-time framework/Groovy/Byte Buddy class-loading warm-up is already in the baseline
 * (a stable first window, not a cold one). The post-close window is measured against the
 * <em>real closed</em> {@code AgentRuntime} (never a null that fabricates zeros).
 *
 * <p>ClassLoader weak-reference counts are split into four honest buckets so the evidence
 * supports the leak claim rather than hiding it in a single total:
 * <ul>
 *   <li>{@code measuredBusiness} - unloadable business {@code URLClassLoader}s created
 *       by the requested scenario cycles (the primary residual signal);</li>
 *   <li>{@code measuredGroovy} - the real {@code KairoGroovyClassLoader} instances the
 *       compiled rules actually used (discovered via reflection, de-duplicated by
 *       identity);</li>
 *   <li>{@code warmupBusiness} / {@code warmupGroovy} - the same kinds created during
 *       the explicit warm-up phase, tracked and accounted separately so they are never
 *       silently folded into the requested-cycle counts.</li>
 * </ul>
 * {@code total} is the grand total across all four buckets (kept for a single honest
 * residual figure). {@link #groovy()} carries the real Groovy compile-cache / generation
 * diagnostics measured from the live (or closed) {@code GroovyScriptCompiler}.
 *
 * @param trackedLoadersTotal      grand total of unloadable ClassLoaders registered so far
 * @param liveTrackedLoaders       grand total still non-null after the GC (residual)
 * @param collectedLoaders         grand total the reference queue reported collected
 */
public record LeakObservation(
        String label,
        boolean postFullGc,
        Instant timestamp,
        long heapUsedBytes,
        long metaspaceUsedBytes,
        int threadCount,
        long openFdCount,
        int loadedClassCount,
        int publishedRuleCount,
        int snapshotCount,
        int journalRecordCount,
        int instrumentationTypeCount,
        int instrumentationMethodCount,
        LoaderCounts measuredBusiness,
        LoaderCounts measuredGroovy,
        LoaderCounts warmupBusiness,
        LoaderCounts warmupGroovy,
        LoaderCounts total,
        GroovyState groovy) {

    /** Tracked / live / collected counts for one ClassLoader bucket. */
    public record LoaderCounts(int tracked, int live, int collected) {
        public static final LoaderCounts ZERO = new LoaderCounts(0, 0, 0);
    }

    /**
     * Real Groovy compile-cache + generation diagnostics measured from the live compiler.
     *
     * @param generationHighWater run-scoped, monotonic high-water of
     *        {@code classesInGeneration} across <em>every</em> successful
     *        {@code GroovyCompilerDiagnostics.measure} call in the run (warm-up + measured
     *        cycles + observations). Unlike {@link #maxClassesInGeneration} (point-in-time,
     *        which collapses to 0 once a bounded GC clears the weakly-held generation holders),
     *        this retains the real peak so the generation-class budget gate observes real
     *        compilation instead of a fabricated zero (&sect;9.3). It is the same value in
     *        every window of a run (the probe owns one monotonic counter).
     */
    public record GroovyState(int cacheEntries, int generationCount, int maxClassesInGeneration,
                              int liveGroovyLoaders, int generationHighWater) {
        public static final GroovyState ZERO = new GroovyState(0, 0, 0, 0, 0);
    }
}
