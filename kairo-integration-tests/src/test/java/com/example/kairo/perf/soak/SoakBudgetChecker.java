package com.example.kairo.perf.soak;

/**
 * The M2-D soak stability gate (&sect;9.4). Evaluates the two time-windowed failure conditions
 * that distinguish a long-running stability gate from a point-in-time leak gate:
 *
 * <ul>
 *   <li><b>Sustained resource-budget breach</b> - "资源持续超过预算": a resource (heap, metaspace,
 *       threads, file descriptors) over budget vs the first stable (baseline) summary window
 *       is a failure only once it has been <em>continuously</em> breached across consecutive
 *       summary windows spanning longer than {@link SoakBudget#sustainedBreachWindowSeconds}.
 *       A short spike that recovers within the window is recorded as evidence but does not
 *       fail. Reuses the M2-C &sect;9.3 resource thresholds.</li>
 *   <li><b>Persistent state drift</b> - "永久状态漂移超过 5 分钟": a state drift (observed
 *       behaviour / rule count diverging from expected) is a failure only once it has persisted
 *       longer than {@link SoakBudget#driftThresholdSeconds}. A transient drift that recovers
 *       within the window does not fail.</li>
 * </ul>
 *
 * <p>Stateful across the summary windows of one run (it owns the baseline and the per-resource
 * breach-start timestamps). The harness calls {@link #evaluate(SoakObservation)} once per
 * 1-minute summary and {@link #evaluateDrift(boolean, long, long)} once per summary with the
 * precisely observed drift start; both are unit-tested by feeding observation sequences.
 *
 * <p>Unsupported metrics (sentinel {@code -1}) never fabricate a pass: a breach is reported
 * as unsupported ({@code false}) and the evidence records the {@code -1}.
 */
public final class SoakBudgetChecker {

    /** A resource breach result vs the baseline, for one summary window. */
    public record Breaches(boolean heap, boolean metaspace, boolean thread, boolean fd) {
        public boolean any() { return heap || metaspace || thread || fd; }
    }

    /** A sustained-breach / persistent-drift failure surfaced by the gate, or {@code null} when none. */
    public record Failure(String phase, String expected, String actual, String detail, long failureSeconds) {
    }

    private final SoakBudget budget;
    private SoakObservation baseline;
    private Long heapBreachStart;
    private Long metaspaceBreachStart;
    private Long threadBreachStart;
    private Long fdBreachStart;

    public SoakBudgetChecker(SoakBudget budget) {
        this.budget = budget;
    }

    /** The baseline window captured so far (the first observation); null until the first evaluate. */
    public SoakObservation baseline() {
        return baseline;
    }

    /**
     * Pure breach computation vs the baseline, reused by the harness (to set the observation's
     * breach flags for evidence) and by {@link #evaluate(SoakObservation)} (to track persistence).
     * Returns all-false before a baseline is established.
     */
    public static Breaches breaches(SoakObservation obs, SoakObservation baseline, SoakBudget budget) {
        if (baseline == null) {
            return new Breaches(false, false, false, false);
        }
        boolean heap = growthPct(obs.heapUsedBytes(), baseline.heapUsedBytes()) > budget.maxHeapGrowthPct();
        boolean metaspace = obs.metaspaceUsedBytes() >= 0 && baseline.metaspaceUsedBytes() >= 0
                && growthPct(obs.metaspaceUsedBytes(), baseline.metaspaceUsedBytes()) > budget.maxMetaspaceGrowthPct();
        boolean thread = obs.threadCount() - baseline.threadCount() > budget.maxThreadDelta();
        boolean fd = obs.openFdCount() >= 0 && baseline.openFdCount() >= 0
                && obs.openFdCount() - baseline.openFdCount() > budget.maxFdDelta();
        return new Breaches(heap, metaspace, thread, fd);
    }

    /**
     * Evaluate one summary window. The first call establishes the baseline. Subsequent calls
     * track per-resource breach persistence and return a {@link Failure} the instant a breach
     * has been sustained longer than {@link SoakBudget#sustainedBreachWindowSeconds}.
     */
    public Failure evaluate(SoakObservation obs) {
        if (baseline == null) {
            baseline = obs;
            return null;
        }
        Breaches b = breaches(obs, baseline, budget);
        long elapsed = obs.elapsedSeconds();
        Failure f = track("heap", b.heap(), heapBreachStart, elapsed, "maxHeapGrowthPct=" + budget.maxHeapGrowthPct());
        if (f != null) {
            heapBreachStart = elapsed;
            return f;
        }
        heapBreachStart = b.heap() ? (heapBreachStart == null ? elapsed : heapBreachStart) : null;
        f = track("metaspace", b.metaspace(), metaspaceBreachStart, elapsed,
                "maxMetaspaceGrowthPct=" + budget.maxMetaspaceGrowthPct());
        if (f != null) {
            metaspaceBreachStart = elapsed;
            return f;
        }
        metaspaceBreachStart = b.metaspace() ? (metaspaceBreachStart == null ? elapsed : metaspaceBreachStart) : null;
        f = track("thread", b.thread(), threadBreachStart, elapsed, "maxThreadDelta=" + budget.maxThreadDelta());
        if (f != null) {
            threadBreachStart = elapsed;
            return f;
        }
        threadBreachStart = b.thread() ? (threadBreachStart == null ? elapsed : threadBreachStart) : null;
        f = track("fd", b.fd(), fdBreachStart, elapsed, "maxFdDelta=" + budget.maxFdDelta());
        if (f != null) {
            fdBreachStart = elapsed;
            return f;
        }
        fdBreachStart = b.fd() ? (fdBreachStart == null ? elapsed : fdBreachStart) : null;
        return null;
    }

    private Failure track(String resource, boolean breached, Long start, long elapsed, String budgetDetail) {
        if (!breached) {
            return null; // caller clears the start timestamp
        }
        long began = start == null ? elapsed : start;
        long sustained = elapsed - began;
        if (sustained > budget.sustainedBreachWindowSeconds()) {
            return new Failure("sustained-resource-breach",
                    "sustained <= " + budget.sustainedBreachWindowSeconds() + "s",
                    resource + " breached " + sustained + "s",
                    "resource " + resource + " over budget for " + sustained + "s (budget " + budgetDetail
                            + ", baseline minute 1); §9.4 sustained breach",
                    elapsed);
        }
        return null;
    }

    /**
     * Evaluate drift persistence. Called once per summary with whether a drift is active, its
     * actual start time, and the run elapsed seconds. Returns a {@link Failure} the instant the drift
     * has persisted longer than {@link SoakBudget#driftThresholdSeconds}. A transient drift
     * (recovers within the window) does not fail.
     */
    public Failure evaluateDrift(boolean drifted, long driftStartedAtSeconds, long elapsedSeconds) {
        if (!drifted) {
            return null;
        }
        long persisted = Math.max(0L, elapsedSeconds - driftStartedAtSeconds);
        if (persisted > budget.driftThresholdSeconds()) {
            return new Failure("persistent-state-drift",
                    "drift <= " + budget.driftThresholdSeconds() + "s",
                    "drift persisted " + persisted + "s",
                    "state drift persisted " + persisted + "s (> " + budget.driftThresholdSeconds()
                            + "s); §9.4 permanent drift",
                    elapsedSeconds);
        }
        return null;
    }

    private static double growthPct(long current, long baseline) {
        if (baseline <= 0) {
            // A non-positive baseline makes a percentage meaningless; treat any growth as a
            // 0% delta so the gate is not falsely tripped by a near-zero baseline.
            return 0.0;
        }
        return ((double) (current - baseline) / baseline) * 100.0;
    }
}
