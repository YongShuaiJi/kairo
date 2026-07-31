package com.example.kairo.perf.soak;

/**
 * The documented M2-D soak resource budgets and stability windows (&sect;9.4). The resource
 * thresholds reuse the M2-C (&sect;9.3) documented values: heap growth, metaspace growth,
 * thread delta and file-descriptor delta are evaluated against the first stable (baseline)
 * summary window, exactly as in the leak gate. The soak adds the two time windows that make a
 * long-running stability gate different from a point-in-time leak gate:
 *
 * <ul>
 *   <li>{@link #driftThresholdSeconds} - "永久状态漂移超过 5 分钟" (&sect;9.4): a state drift
 *       (observed behaviour / rule count / unload outcome diverging from expected) is a
 *       failure only once it has persisted longer than this window. A transient drift that
 *       recovers within the window does not fail the gate (it is recorded as evidence).</li>
 *   <li>{@link #sustainedBreachWindowSeconds} - "资源持续超过预算" (&sect;9.4): a resource
 *       breach (heap/metaspace/thread/fd over budget vs the baseline) is a failure only once
 *       it has been sustained across consecutive summary windows spanning longer than this
 *       window. A short spike that recovers within the window does not fail.</li>
 * </ul>
 *
 * <p>As with the M2-C budgets, these must NOT be weakened by the harness: a sustained breach
 * or a persistent drift records the failing gate and exits non-zero rather than relaxing a
 * threshold. A budget adjustment requires a documented review with raw samples.
 *
 * <p>The {@code -1} sentinel marks an unsupported metric (e.g. file descriptors when the JVM
 * does not expose {@code UnixOperatingSystemMXBean}); a breach of an unsupported metric is
 * reported as unsupported, never fabricated as a pass.
 *
 * @param maxHeapGrowthPct           max heap growth vs first stable window, percent
 * @param maxMetaspaceGrowthPct      max metaspace growth vs first stable window, percent
 * @param maxThreadDelta             max running-thread delta vs first stable window
 * @param maxFdDelta                 max file-descriptor delta vs first stable window
 * @param driftThresholdSeconds      drift must persist longer than this to fail (&sect;9.4: 5 min)
 * @param sustainedBreachWindowSeconds a resource breach must be sustained longer than this to fail
 */
public record SoakBudget(int maxHeapGrowthPct, int maxMetaspaceGrowthPct, int maxThreadDelta, int maxFdDelta,
                         int driftThresholdSeconds, int sustainedBreachWindowSeconds) {

    /** The documented M2-D soak budgets (&sect;9.4), reusing the M2-C &sect;9.3 resource thresholds. */
    public static final SoakBudget DOCUMENTED = new SoakBudget(
            15,  // heap growth vs first stable window, percent (§9.3)
            10,  // metaspace growth vs first stable window, percent (§9.3)
            2,   // running-thread delta vs first stable window (§9.3)
            5,   // file-descriptor delta vs first stable window (§9.3)
            300, // drift must persist > 5 minutes to fail (§9.4)
            300  // a resource breach must be sustained > 5 minutes to fail (§9.4)
    );
}
