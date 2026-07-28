package com.example.kairo.perf.statecycle;

import java.util.List;

/**
 * Single source of truth for the M2-B state-cycle scenario matrix (&sect;9.2).
 *
 * <p>The matrix is fixed at six scenarios: five cyclic scenarios that each run a
 * real enhance / invoke / update-or-partial-unload / full-unload / hash-restore
 * lifecycle, plus one concurrent-conflict scenario that runs exactly once per
 * invocation. The harness, the result validator and the shell runner all key off
 * the ids declared here so the evidence cannot drift from the matrix.
 *
 * <p>{@link #distribute(int)} allocates a requested cycle budget across the five
 * cyclic scenarios deterministically (each receives at least one cycle), and the
 * concurrent scenario always receives exactly one. {@code --cycles 500} is therefore
 * 500 total cycles distributed across the matrix, not 500 repetitions of every
 * scenario.
 */
public final class StateCycleScenarioCatalog {

    /** Minimum cycle count: every scenario must run at least once (5 cyclic + 1 concurrent). */
    public static final int MIN_CYCLES = 6;

    public record Scenario(String id, String category, String description, boolean concurrent) {
    }

    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("ordinary-method", "method",
                    "ordinary method: enhance, invoke, content-only update, full unload, hash restore", false),
            new Scenario("constructor-enhancement", "constructor",
                    "V1.3 constructor (CONSTRUCTOR_THROW) enhance, update, full unload, hash restore", false),
            new Scenario("callsite-enhancement", "callsite",
                    "V1.3 call-site (CALL_BEFORE) enhance, update, full unload, hash restore", false),
            new Scenario("rule-chain", "chain",
                    "multi-rule chain apply, partial unload keeping exact remaining behavior, full unload", false),
            new Scenario("parent-child-loader", "classloader",
                    "parent/child same-name ClassLoaders, targeted by loader identity; only the selected loader changes; both restore", false),
            new Scenario("concurrent-conflict", "concurrency",
                    "competing chain apply from multiple threads; exactly one wins; no corrupted mixed state; full unload restores", true));

    private StateCycleScenarioCatalog() {
    }

    public static List<Scenario> all() {
        return SCENARIOS;
    }

    public static List<String> ids() {
        return SCENARIOS.stream().map(Scenario::id).toList();
    }

    public static List<Scenario> cyclicScenarios() {
        return SCENARIOS.stream().filter(s -> !s.concurrent).toList();
    }

    public static Scenario concurrentScenario() {
        return SCENARIOS.stream().filter(Scenario::concurrent).findFirst()
                .orElseThrow(() -> new IllegalStateException("no concurrent scenario declared"));
    }

    public static Scenario get(String id) {
        return SCENARIOS.stream().filter(s -> s.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown state-cycle scenario: " + id));
    }

    /**
     * Distribute {@code cycles} across the matrix in catalog order. The five cyclic
     * scenarios share {@code cycles - 1} (each at least one); the concurrent scenario
     * always receives exactly one. The remainder of the cyclic budget is spread over
     * the first cyclic scenarios so the split is deterministic and stable.
     *
     * @throws IllegalArgumentException if {@code cycles < MIN_CYCLES}
     */
    public static int[] distribute(int cycles) {
        if (cycles < MIN_CYCLES) {
            throw new IllegalArgumentException(
                    "cycles must be >= " + MIN_CYCLES + " so every scenario runs at least once (got " + cycles + ")");
        }
        int[] counts = new int[SCENARIOS.size()];
        int cyclicBudget = cycles - 1;
        int cyclicCount = (int) cyclicScenarios().size();
        int base = cyclicBudget / cyclicCount;
        int remainder = cyclicBudget % cyclicCount;
        int cyclicIndex = 0;
        for (int i = 0; i < SCENARIOS.size(); i++) {
            if (SCENARIOS.get(i).concurrent()) {
                counts[i] = 1;
            } else {
                counts[i] = base + (cyclicIndex < remainder ? 1 : 0);
                cyclicIndex++;
            }
        }
        return counts;
    }
}
