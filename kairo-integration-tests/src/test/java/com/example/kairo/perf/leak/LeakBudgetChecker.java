package com.example.kairo.perf.leak;

import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates the observed resource windows against the documented M2-C budgets
 * (&sect;9.3) and produces a list of named gate results. A run passes only when every
 * applicable gate passes; the first failing gate is surfaced as the run's
 * {@code firstFailure} so the evidence names the exact budget that was breached.
 *
 * <p>Platform-unsupported observations (file descriptors or metaspace on a JVM that
 * does not expose them) are reported as {@code passed=true} with {@code observed=-1}
 * and a detail noting they are unsupported, rather than fabricated or failed
 * (&sect;9.3: "文件描述符（平台支持时）").
 *
 * <p>Growth gates compare the last stable window (post-cycles, agent alive) against the
 * first stable window (baseline, agent alive, captured <em>after warm-up</em>), both
 * after a full GC. The residual-ClassLoader and cache-cleared gates use the post-close
 * window (agent closed, final bounded GC). The cache-budget gates check that every window
 * stays at or below the product's bounded-cache limits.
 *
 * <p><b>Defect signal preserved.</b> {@code residual-classloaders} is evaluated first. Per
 * &sect;9.3 the residual budget includes <em>every</em> explicitly created unloadable
 * ClassLoader of that kind, so it counts the measured business loaders <em>plus</em> the
 * warm-up business loaders (both pinned by the known Groovy-invoke leak); the known leak
 * makes it fail until {@code bugfix/v1.7-groovy-invoke-classloader-leak} lands. The Groovy
 * gates are additional evidence (the cache/generation bounds hold and clear on close even
 * though the loaders themselves stay pinned); {@code residual-groovy-loaders} is the
 * Groovy-loader counterpart of the residual signal (measured + warm-up Groovy loaders). No
 * documented threshold is weakened - gates are only added.
 */
public final class LeakBudgetChecker {

    public record GateResult(String name, boolean passed, String observed, String budget, String detail) {
    }

    public record Verdict(List<GateResult> gates, GateResult firstFailure, boolean overallPassed) {
    }

    private final LeakBudget budget;

    public LeakBudgetChecker(LeakBudget budget) {
        this.budget = budget;
    }

    /**
     * Evaluate the run. {@code baseline} is the first stable window (after warm-up),
     * {@code postCycles} the last stable window (agent alive), {@code postClose} the
     * post-close window (real closed runtime), and {@code all} every captured window
     * (for cache-budget bounds).
     */
    public Verdict evaluate(LeakObservation baseline, LeakObservation postCycles,
                            LeakObservation postClose, List<LeakObservation> all) {
        List<GateResult> gates = new ArrayList<>();
        // Residual signals first: residual-classloaders must remain firstFailure for the
        // known leak so the short gate keeps exiting non-zero until the bugfix lands.
        gates.add(residualClassLoaders(postClose));
        gates.add(residualGroovyLoaders(postClose));
        gates.add(threadDelta(baseline, postCycles));
        gates.add(fdDelta(baseline, postCycles));
        gates.add(heapGrowth(baseline, postCycles));
        gates.add(metaspaceGrowth(baseline, postCycles));
        gates.add(rulesCleared(postCycles));
        gates.add(instrumentationCleared(postCycles));
        gates.add(snapshotBudget(all));
        gates.add(journalBudget(all));
        gates.add(snapshotClearedOnClose(postClose));
        gates.add(groovyCacheBudget(all));
        gates.add(groovyGenerationClassBudget(all));
        gates.add(groovyCacheClearedOnClose(postClose));
        GateResult firstFailure = null;
        for (GateResult g : gates) {
            if (!g.passed() && firstFailure == null) {
                firstFailure = g;
            }
        }
        return new Verdict(gates, firstFailure, firstFailure == null);
    }

    private GateResult residualClassLoaders(LeakObservation postClose) {
        // §9.3: every explicitly created unloadable ClassLoader of this kind is in the
        // residual budget. The harness explicitly creates and weak-tracks both the measured
        // (requested-cycle) and the warm-up business loaders, so both live counts are summed.
        // The separate measured/warm-up breakdown is preserved in the evidence buckets; the
        // gate observed value reconciles to the sum.
        int measuredLive = postClose.measuredBusiness().live();
        int warmupLive = postClose.warmupBusiness().live();
        int residual = measuredLive + warmupLive;
        boolean pass = residual <= budget.maxResidualClassLoaders();
        return new GateResult("residual-classloaders", pass,
                String.valueOf(residual), "<= " + budget.maxResidualClassLoaders(),
                "live business ClassLoaders after final bounded GC + close = measured live ("
                        + measuredLive + ") + warm-up live (" + warmupLive + ") = " + residual
                        + " (§9.3 residual budget includes every explicitly created loader of"
                        + " this kind; measured business tracked=" + postClose.measuredBusiness().tracked()
                        + ", collected=" + postClose.measuredBusiness().collected()
                        + "; warm-up business tracked=" + postClose.warmupBusiness().tracked()
                        + ", collected=" + postClose.warmupBusiness().collected() + ")");
    }

    private GateResult residualGroovyLoaders(LeakObservation postClose) {
        // §9.3 counterpart of residual-classloaders for the real KairoGroovyClassLoader
        // instances: measured + warm-up Groovy loaders are both explicitly created and so both
        // live counts are summed. Warm-up loaders can never be present yet omitted.
        int measuredLive = postClose.measuredGroovy().live();
        int warmupLive = postClose.warmupGroovy().live();
        int residual = measuredLive + warmupLive;
        boolean pass = residual <= budget.maxResidualClassLoaders();
        return new GateResult("residual-groovy-loaders", pass,
                String.valueOf(residual), "<= " + budget.maxResidualClassLoaders(),
                "live Groovy ClassLoaders (KairoGroovyClassLoader) after close = measured live ("
                        + measuredLive + ") + warm-up live (" + warmupLive + ") = " + residual
                        + " (§9.3 residual budget includes every explicitly created loader of"
                        + " this kind; measured groovy tracked=" + postClose.measuredGroovy().tracked()
                        + ", collected=" + postClose.measuredGroovy().collected()
                        + "; warm-up groovy tracked=" + postClose.warmupGroovy().tracked()
                        + ", collected=" + postClose.warmupGroovy().collected() + ")");
    }

    private GateResult threadDelta(LeakObservation baseline, LeakObservation postCycles) {
        int delta = postCycles.threadCount() - baseline.threadCount();
        boolean pass = Math.abs(delta) <= budget.maxThreadDelta();
        return new GateResult("thread-delta", pass,
                String.valueOf(delta), "|delta| <= " + budget.maxThreadDelta(),
                "baseline=" + baseline.threadCount() + " post-cycles=" + postCycles.threadCount());
    }

    private GateResult fdDelta(LeakObservation baseline, LeakObservation postCycles) {
        if (baseline.openFdCount() < 0 || postCycles.openFdCount() < 0) {
            return new GateResult("fd-delta", true, "-1", "<= " + budget.maxFdDelta(),
                    "unsupported on this JVM (no UnixOperatingSystemMXBean)");
        }
        long delta = postCycles.openFdCount() - baseline.openFdCount();
        boolean pass = Math.abs(delta) <= budget.maxFdDelta();
        return new GateResult("fd-delta", pass,
                String.valueOf(delta), "|delta| <= " + budget.maxFdDelta(),
                "baseline=" + baseline.openFdCount() + " post-cycles=" + postCycles.openFdCount());
    }

    private GateResult heapGrowth(LeakObservation baseline, LeakObservation postCycles) {
        if (baseline.heapUsedBytes() <= 0) {
            return new GateResult("heap-growth", true, "n/a", "<= " + budget.maxHeapGrowthPct() + "%",
                    "baseline heap non-positive; gate skipped");
        }
        long delta = postCycles.heapUsedBytes() - baseline.heapUsedBytes();
        double pct = delta * 100.0 / baseline.heapUsedBytes();
        boolean pass = pct <= budget.maxHeapGrowthPct();
        return new GateResult("heap-growth", pass,
                String.format("%.2f%%", pct), "<= " + budget.maxHeapGrowthPct() + "%",
                "baseline=" + baseline.heapUsedBytes() + " post-cycles=" + postCycles.heapUsedBytes());
    }

    private GateResult metaspaceGrowth(LeakObservation baseline, LeakObservation postCycles) {
        if (baseline.metaspaceUsedBytes() < 0 || postCycles.metaspaceUsedBytes() < 0) {
            return new GateResult("metaspace-growth", true, "-1", "<= " + budget.maxMetaspaceGrowthPct() + "%",
                    "unsupported on this JVM (no Metaspace memory pool)");
        }
        if (baseline.metaspaceUsedBytes() == 0) {
            return new GateResult("metaspace-growth", true, "n/a", "<= " + budget.maxMetaspaceGrowthPct() + "%",
                    "baseline metaspace zero; gate skipped");
        }
        long delta = postCycles.metaspaceUsedBytes() - baseline.metaspaceUsedBytes();
        double pct = delta * 100.0 / baseline.metaspaceUsedBytes();
        boolean pass = pct <= budget.maxMetaspaceGrowthPct();
        return new GateResult("metaspace-growth", pass,
                String.format("%.2f%%", pct), "<= " + budget.maxMetaspaceGrowthPct() + "%",
                "baseline=" + baseline.metaspaceUsedBytes() + " post-cycles=" + postCycles.metaspaceUsedBytes());
    }

    private GateResult rulesCleared(LeakObservation postCycles) {
        int rules = postCycles.publishedRuleCount();
        boolean pass = rules == 0;
        return new GateResult("rules-cleared", pass, String.valueOf(rules), "== 0",
                "published rules after all cycles unloaded (rule registry leak)");
    }

    private GateResult instrumentationCleared(LeakObservation postCycles) {
        int types = postCycles.instrumentationTypeCount();
        int methods = postCycles.instrumentationMethodCount();
        boolean pass = types == 0 && methods == 0;
        return new GateResult("instrumentation-cleared", pass,
                "types=" + types + ",methods=" + methods, "types==0,methods==0",
                "instrumentation/method cache after full unload (returns to baseline)");
    }

    private GateResult snapshotBudget(List<LeakObservation> all) {
        int max = all.stream().mapToInt(LeakObservation::snapshotCount).max().orElse(0);
        boolean pass = max <= budget.snapshotMaxEntries();
        return new GateResult("snapshot-budget", pass,
                String.valueOf(max), "<= " + budget.snapshotMaxEntries(),
                "max bytecode-snapshot entries across all windows (product maxEntries budget)");
    }

    private GateResult journalBudget(List<LeakObservation> all) {
        int max = all.stream().mapToInt(LeakObservation::journalRecordCount).max().orElse(0);
        boolean pass = max <= budget.journalMaxRecords();
        return new GateResult("journal-budget", pass,
                String.valueOf(max), "<= " + budget.journalMaxRecords(),
                "max transformation-journal records across all windows (product globalLimit budget)");
    }

    private GateResult snapshotClearedOnClose(LeakObservation postClose) {
        int count = postClose.snapshotCount();
        boolean pass = count == 0;
        return new GateResult("snapshot-cleared-on-close", pass,
                String.valueOf(count), "== 0",
                "bytecode-snapshot repository cleared by AgentRuntime.close() (resource release)");
    }

    private GateResult groovyCacheBudget(List<LeakObservation> all) {
        int max = all.stream().mapToInt(o -> o.groovy().cacheEntries()).max().orElse(0);
        boolean pass = max <= budget.groovyCacheMaxEntries();
        return new GateResult("groovy-cache-budget", pass,
                String.valueOf(max), "<= " + budget.groovyCacheMaxEntries(),
                "max Groovy compile-cache entries across all windows (product MAX_CACHE_ENTRIES budget)");
    }

    private GateResult groovyGenerationClassBudget(List<LeakObservation> all) {
        // Use the run-scoped, monotonic high-water (carried in every observation's GroovyState)
        // rather than the point-in-time maxClassesInGeneration, which a bounded GC collapses to
        // 0 once the weakly-held generation holders are cleared. The high-water is the same
        // across all windows (one monotonic probe counter), so the max is the run peak.
        int max = all.stream().mapToInt(o -> o.groovy().generationHighWater()).max().orElse(0);
        boolean pass = max <= budget.generationMaxClasses();
        return new GateResult("groovy-generation-class-budget", pass,
                String.valueOf(max), "<= " + budget.generationMaxClasses(),
                "max classes in any one Groovy generation across the run (run-scoped high-water; "
                        + "product MAX_CLASSES_PER_GENERATION budget)");
    }

    private GateResult groovyCacheClearedOnClose(LeakObservation postClose) {
        int cache = postClose.groovy().cacheEntries();
        int generations = postClose.groovy().generationCount();
        boolean pass = cache == 0 && generations == 0;
        return new GateResult("groovy-cache-cleared-on-close", pass,
                "cache=" + cache + ",generations=" + generations, "cache==0,generations==0",
                "Groovy compile cache + generations cleared by compiler.close() on AgentRuntime.close()");
    }
}
