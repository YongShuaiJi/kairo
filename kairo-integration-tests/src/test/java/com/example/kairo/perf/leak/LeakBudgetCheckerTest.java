package com.example.kairo.perf.leak;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic tests for {@link LeakBudgetChecker}. Constructs observation windows
 * directly (no JVM) and asserts each documented gate passes or fails precisely on the
 * observed value, including the new Groovy cache/generation/cleared and residual-groovy
 * gates. The budgets under test are the documented &sect;9.3 values
 * ({@link LeakBudget#DOCUMENTED}); a failing gate must surface as
 * {@code verdict.firstFailure()} with the exact gate name, and {@code residual-classloaders}
 * must stay first so the known-leak firstFailure is preserved.
 */
class LeakBudgetCheckerTest {

    private static final LeakBudget BUDGET = LeakBudget.DOCUMENTED;

    // 10 MiB baseline; post-cycles stays well within the 15%/10% growth budgets.
    private static final long BASE_HEAP = 10L * 1024 * 1024;
    private static final long BASE_META = 10L * 1024 * 1024;

    private static final LeakObservation.LoaderCounts ZERO = LeakObservation.LoaderCounts.ZERO;
    private static final LeakObservation.GroovyState NO_GROOVY = LeakObservation.GroovyState.ZERO;

    private static LeakObservation.LoaderCounts bucket(int tracked, int live, int collected) {
        return new LeakObservation.LoaderCounts(tracked, live, collected);
    }

    private static LeakObservation.GroovyState groovy(int cache, int generations, int maxClasses) {
        // In a single deterministic window the run-scoped high-water equals the point-in-time
        // max (no prior measurement to dominate).
        return new LeakObservation.GroovyState(cache, generations, maxClasses, 0, maxClasses);
    }

    private static LeakObservation window(String label, long heap, long meta, int threads, long fd,
                                          int rules, int snapshot, int journal, int types, int methods,
                                          LeakObservation.LoaderCounts mb, LeakObservation.LoaderCounts mg,
                                          LeakObservation.LoaderCounts wb, LeakObservation.LoaderCounts wg,
                                          LeakObservation.GroovyState groovy) {
        LeakObservation.LoaderCounts total = new LeakObservation.LoaderCounts(
                mb.tracked() + mg.tracked() + wb.tracked() + wg.tracked(),
                mb.live() + mg.live() + wb.live() + wg.live(),
                mb.collected() + mg.collected() + wb.collected() + wg.collected());
        return new LeakObservation(label, true, Instant.EPOCH, heap, meta, threads, fd,
                1000, rules, snapshot, journal, types, methods, mb, mg, wb, wg, total, groovy);
    }

    private static LeakObservation baseline() {
        return window("baseline", BASE_HEAP, BASE_META, 10, 100,
                0, 0, 0, 0, 0, bucket(100, 0, 0), ZERO, ZERO, ZERO, NO_GROOVY);
    }

    private static LeakObservation postCycles(long heap, long meta, int threads, long fd,
                                              int rules, int snapshot, int journal, int types, int methods) {
        return window("post-cycles", heap, meta, threads, fd,
                rules, snapshot, journal, types, methods, bucket(100, 0, 100), ZERO, ZERO, ZERO, NO_GROOVY);
    }

    private static LeakObservation postClose(int businessLive, int snapshot) {
        return postClose(businessLive, snapshot, 0, 0, 0);
    }

    private static LeakObservation postClose(int businessLive, int snapshot,
                                             int groovyCache, int groovyGenerations, int groovyMaxClasses) {
        return window("post-close", 0, 0, 8, 99,
                0, snapshot, 0, 0, 0, bucket(100, businessLive, 100 - businessLive), ZERO, ZERO, ZERO,
                groovy(groovyCache, groovyGenerations, groovyMaxClasses));
    }

    private static LeakBudgetChecker.Verdict evaluate(LeakObservation baseline,
                                                      LeakObservation postCycles, LeakObservation postClose) {
        return new LeakBudgetChecker(BUDGET).evaluate(baseline, postCycles, postClose,
                List.of(baseline, postCycles, postClose));
    }

    private static boolean passed(LeakBudgetChecker.Verdict v, String gate) {
        return v.gates().stream().filter(g -> g.name().equals(gate))
                .findFirst().map(LeakBudgetChecker.GateResult::passed).orElse(false);
    }

    private static LeakBudgetChecker.GateResult findGate(LeakBudgetChecker.Verdict v, String gate) {
        return v.gates().stream().filter(g -> g.name().equals(gate))
                .findFirst().orElseThrow(() -> new AssertionError("gate not found: " + gate));
    }

    @Test
    void allGatesPassForCleanRun() {
        LeakObservation post = postCycles(BASE_HEAP + BASE_HEAP / 20, BASE_META + BASE_META / 20,
                11, 103, 0, 200, 3000, 0, 0);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 0));
        assertThat(v.overallPassed()).isTrue();
        assertThat(v.firstFailure()).isNull();
    }

    @Test
    void residualClassLoadersGateFailsWhenAboveBudget() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(5, 0));
        assertThat(passed(v, "residual-classloaders")).isFalse();
        assertThat(v.overallPassed()).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("residual-classloaders");
    }

    @Test
    void residualGroovyLoadersGateFailsWhenAboveBudget() {
        // Measured business collected (residual-classloaders passes), but Groovy loaders pinned.
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0);
        LeakObservation close = window("post-close", 0, 0, 8, 99, 0, 0, 0, 0, 0,
                bucket(100, 0, 100), bucket(6, 6, 0), ZERO, ZERO, NO_GROOVY);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, close);
        assertThat(passed(v, "residual-groovy-loaders")).isFalse();
        // residual-classloaders passes (business collected), so groovy residual is the first failure.
        assertThat(v.firstFailure().name()).isEqualTo("residual-groovy-loaders");
    }

    @Test
    void residualClassLoadersGateIncludesWarmUpLoaders() {
        // §9.3: the residual budget includes EVERY explicitly created business loader. Measured
        // loaders are all collected (0 live), but 3 warm-up loaders are pinned. The gate must
        // observe measured + warm-up = 3 (not 0), failing the <= 2 budget.
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0);
        LeakObservation close = window("post-close", 0, 0, 8, 99, 0, 0, 0, 0, 0,
                bucket(100, 0, 100), ZERO, bucket(6, 3, 3), ZERO, NO_GROOVY);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, close);
        LeakBudgetChecker.GateResult g = findGate(v, "residual-classloaders");
        assertThat(g.observed()).isEqualTo("3");
        assertThat(g.passed()).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("residual-classloaders");
    }

    @Test
    void residualGroovyLoadersGateIncludesWarmUpLoaders() {
        // Measured Groovy loaders all collected (0 live), but 3 warm-up Groovy loaders pinned.
        // Business is clean (0 live), so the Groovy residual = 0 + 3 = 3 is the first failure.
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0);
        LeakObservation close = window("post-close", 0, 0, 8, 99, 0, 0, 0, 0, 0,
                bucket(100, 0, 100), bucket(6, 0, 6), ZERO, bucket(6, 3, 3), NO_GROOVY);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, close);
        LeakBudgetChecker.GateResult g = findGate(v, "residual-groovy-loaders");
        assertThat(g.observed()).isEqualTo("3");
        assertThat(g.passed()).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("residual-groovy-loaders");
    }

    @Test
    void residualClassLoadersGateReconcilesMeasuredPlusWarmUp() {
        // Both measured and warm-up business loaders pinned: gate observes the full sum.
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0);
        LeakObservation close = window("post-close", 0, 0, 8, 99, 0, 0, 0, 0, 0,
                bucket(100, 4, 96), ZERO, bucket(6, 2, 4), ZERO, NO_GROOVY);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, close);
        LeakBudgetChecker.GateResult g = findGate(v, "residual-classloaders");
        assertThat(g.observed()).isEqualTo("6"); // 4 measured + 2 warm-up
        assertThat(g.passed()).isFalse(); // 6 > 2
    }

    @Test
    void threadDeltaGateFailsWhenAboveBudget() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 13, 100, 0, 0, 0, 0, 0);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 0));
        assertThat(passed(v, "thread-delta")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("thread-delta");
    }

    @Test
    void fdDeltaGateFailsWhenAboveBudget() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 106, 0, 0, 0, 0, 0);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 0));
        assertThat(passed(v, "fd-delta")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("fd-delta");
    }

    @Test
    void heapGrowthGateFailsWhenAboveBudget() {
        LeakObservation post = postCycles(BASE_HEAP + BASE_HEAP / 2, BASE_META, 10, 100, 0, 0, 0, 0, 0);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 0));
        assertThat(passed(v, "heap-growth")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("heap-growth");
    }

    @Test
    void metaspaceGrowthGateFailsWhenAboveBudget() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META + BASE_META / 4, 10, 100, 0, 0, 0, 0, 0);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 0));
        assertThat(passed(v, "metaspace-growth")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("metaspace-growth");
    }

    @Test
    void rulesClearedGateFailsWhenRulesLeak() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 3, 0, 0, 0, 0);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 0));
        assertThat(passed(v, "rules-cleared")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("rules-cleared");
    }

    @Test
    void instrumentationClearedGateFailsWhenMethodCacheRetained() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 2, 3);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 0));
        assertThat(passed(v, "instrumentation-cleared")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("instrumentation-cleared");
    }

    @Test
    void snapshotBudgetGateFailsWhenAboveMaxEntries() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0,
                BUDGET.snapshotMaxEntries() + 1, 0, 0, 0);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 0));
        assertThat(passed(v, "snapshot-budget")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("snapshot-budget");
    }

    @Test
    void journalBudgetGateFailsWhenAboveGlobalLimit() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0,
                BUDGET.journalMaxRecords() + 1, 0, 0);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 0));
        assertThat(passed(v, "journal-budget")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("journal-budget");
    }

    @Test
    void snapshotClearedOnCloseGateFailsWhenNotCleared() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0);
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 7));
        assertThat(passed(v, "snapshot-cleared-on-close")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("snapshot-cleared-on-close");
    }

    @Test
    void groovyCacheBudgetGateFailsWhenAboveMaxEntries() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0);
        // A post-cycle window with cache above the product MAX_CACHE_ENTRIES budget.
        LeakObservation hot = window("after-groovy-compile-cache", BASE_HEAP, BASE_META, 10, 100,
                0, 0, 0, 0, 0, bucket(100, 0, 100), bucket(1, 1, 0), ZERO, ZERO,
                groovy(BUDGET.groovyCacheMaxEntries() + 1, 1, 1));
        LeakBudgetChecker.Verdict v = new LeakBudgetChecker(BUDGET).evaluate(baseline(), post,
                postClose(0, 0), List.of(baseline(), hot, post, postClose(0, 0)));
        assertThat(passed(v, "groovy-cache-budget")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("groovy-cache-budget");
    }

    @Test
    void groovyGenerationClassBudgetGateFailsWhenAboveMax() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0);
        LeakObservation hot = window("after-groovy-compile-cache", BASE_HEAP, BASE_META, 10, 100,
                0, 0, 0, 0, 0, bucket(100, 0, 100), bucket(1, 1, 0), ZERO, ZERO,
                groovy(1, 1, BUDGET.generationMaxClasses() + 1));
        LeakBudgetChecker.Verdict v = new LeakBudgetChecker(BUDGET).evaluate(baseline(), post,
                postClose(0, 0), List.of(baseline(), hot, post, postClose(0, 0)));
        assertThat(passed(v, "groovy-generation-class-budget")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("groovy-generation-class-budget");
    }

    @Test
    void groovyGenerationClassBudgetUsesRunHighWaterNotPointInTimeZero() {
        // The point-in-time groovyMaxClassesInGeneration is 0 in every window (weakly-held
        // generation holders cleared by GC), but the run-scoped high-water is 8 in the
        // post-cycles window. The gate must use the high-water (8), not the fabricated-zero
        // point-in-time max.
        LeakObservation.GroovyState pointInTimeZero = new LeakObservation.GroovyState(0, 0, 0, 0, 8);
        LeakObservation post = window("post-cycles", BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0,
                bucket(100, 0, 100), ZERO, ZERO, ZERO, pointInTimeZero);
        LeakBudgetChecker.Verdict v = new LeakBudgetChecker(BUDGET).evaluate(baseline(), post,
                postClose(0, 0), List.of(baseline(), post, postClose(0, 0)));
        LeakBudgetChecker.GateResult g = findGate(v, "groovy-generation-class-budget");
        assertThat(g.observed()).isEqualTo("8");
        assertThat(g.passed()).isTrue(); // 8 <= 256
    }

    @Test
    void groovyCacheClearedOnCloseGateFailsWhenNotCleared() {
        LeakObservation post = postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0);
        // Post-close with a non-empty cache/generation (close did not clear).
        LeakBudgetChecker.Verdict v = evaluate(baseline(), post, postClose(0, 0, 3, 1, 0));
        assertThat(passed(v, "groovy-cache-cleared-on-close")).isFalse();
        assertThat(v.firstFailure().name()).isEqualTo("groovy-cache-cleared-on-close");
    }

    @Test
    void unsupportedFdObservationIsReportedNotFailed() {
        LeakObservation b = new LeakObservation("baseline", true, Instant.EPOCH,
                BASE_HEAP, BASE_META, 10, -1L, 1000, 0, 0, 0, 0, 0,
                bucket(100, 0, 0), ZERO, ZERO, ZERO, bucket(100, 0, 0), NO_GROOVY);
        LeakObservation p = new LeakObservation("post-cycles", true, Instant.EPOCH,
                BASE_HEAP, BASE_META, 11, -1L, 1000, 0, 0, 0, 0, 0,
                bucket(100, 0, 100), ZERO, ZERO, ZERO, bucket(100, 0, 100), NO_GROOVY);
        LeakBudgetChecker.Verdict v = evaluate(b, p, postClose(0, 0));
        assertThat(passed(v, "fd-delta")).as("unsupported fd gate must pass, not fail").isTrue();
    }

    @Test
    void completeGateSetIsEmitted() {
        LeakBudgetChecker.Verdict v = evaluate(baseline(),
                postCycles(BASE_HEAP, BASE_META, 10, 100, 0, 0, 0, 0, 0), postClose(0, 0));
        List<String> names = v.gates().stream().map(LeakBudgetChecker.GateResult::name).toList();
        assertThat(names).containsExactly("residual-classloaders", "residual-groovy-loaders",
                "thread-delta", "fd-delta", "heap-growth", "metaspace-growth",
                "rules-cleared", "instrumentation-cleared",
                "snapshot-budget", "journal-budget", "snapshot-cleared-on-close",
                "groovy-cache-budget", "groovy-generation-class-budget", "groovy-cache-cleared-on-close");
    }
}
