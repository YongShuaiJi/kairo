package com.example.kairo.perf;

import java.util.List;

/**
 * The deterministic set of benchmark scenarios covering the §9.1 measurement
 * matrix. This is the single source of truth for scenario ids — the harness,
 * reporter, validator and budget all key off it. Adding a scenario here makes it
 * appear in the completeness test; it is not added anywhere else by hand.
 *
 * <p>Every scenario is drivable through the public {@code AgentRuntime} API that
 * exists in both V1.6.0 and HEAD (publish/remove/rules/events/metrics/searchClasses/
 * loadedClassRepository/recordEvent). No scenario substitutes a Map-only or
 * sleep-only stub.
 */
public final class ScenarioCatalog {

    private ScenarioCatalog() { }

    /** The full catalog, in §9.1 matrix order. */
    public static List<Scenario> all() {
        return List.of(
                new Scenario("no-agent-baseline", "baseline",
                        "Target method called with no Agent loaded; pure JVM baseline.",
                        true, false, 20_000, "call"),
                new Scenario("agent-loaded-not-enhanced", "baseline",
                        "Agent loaded and started, but target method has no rule (not instrumented).",
                        true, false, 20_000, "call"),
                new Scenario("enhanced-rule-not-matched", "dispatch",
                        "Target enhanced with a percentage=0 rule; method is instrumented but the rule is sampled out every call (miss).",
                        true, true, 20_000, "call"),
                new Scenario("before-hit", "dispatch",
                        "BEFORE rule that proceeds; measures BEFORE-phase hit path.",
                        true, true, 20_000, "call"),
                new Scenario("return-hit", "dispatch",
                        "RETURN rule that replaces the return value; measures RETURN-phase hit path.",
                        true, true, 20_000, "call"),
                new Scenario("throws-hit", "dispatch",
                        "Target throws a cached exception; THROWS rule converts it to a return value.",
                        true, true, 20_000, "call"),
                new Scenario("groovy-noop", "groovy",
                        "BEFORE Groovy script that does nothing and proceeds; baseline of the script-complexity series.",
                        true, true, 20_000, "call"),
                new Scenario("groovy-arg-read", "groovy",
                        "BEFORE Groovy script that reads args and method metadata, then proceeds.",
                        true, true, 20_000, "call"),
                new Scenario("groovy-return-replace", "groovy",
                        "BEFORE Groovy script that replaces the return value and skips the original body.",
                        true, true, 20_000, "call"),
                new Scenario("chain-1", "chain",
                        "Single-rule chain (1 BEFORE proceed rule).",
                        true, true, 20_000, "call"),
                new Scenario("chain-5", "chain",
                        "5-rule chain of BEFORE proceed rules; measures per-rule chain iteration cost.",
                        true, true, 20_000, "call"),
                new Scenario("chain-20", "chain",
                        "20-rule chain of BEFORE proceed rules; measures per-rule chain iteration cost.",
                        true, true, 20_000, "call"),
                new Scenario("inventory-query", "inventory",
                        "Large loaded-class/rule inventory query: rules() + searchClasses + live loader enumeration.",
                        true, false, 1, "query"),
                new Scenario("event-buffer-full-drop", "buffer",
                        "Record events into an already-full RuntimeEventBuffer; each record drops the oldest (drop-oldest cost).",
                        true, false, 1_000, "event-record")
        );
    }

    public static List<String> ids() {
        return all().stream().map(Scenario::id).toList();
    }

    public static Scenario get(String id) {
        for (Scenario s : all()) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown scenario: " + id);
    }

    /**
     * One benchmark scenario definition.
     *
     * @param id              stable scenario identifier
     * @param category        matrix group (baseline / dispatch / groovy / chain / inventory / buffer)
     * @param description     human-readable purpose
     * @param comparable      true if a V1.6.0 baseline exists (all are, by construction)
     * @param gated           true if the median/P95 regression budget GATES this scenario
     *                        (key hit/miss paths); false = observed-only (still mandatory &
     *                        schema-validated, but not budget-gated)
     * @param opsPerIteration operations performed in one measurement iteration; defines "op"
     * @param opsLabel        unit label for this scenario's "op" (call / query / event-record)
     */
    record Scenario(
            String id,
            String category,
            String description,
            boolean comparable,
            boolean gated,
            int opsPerIteration,
            String opsLabel) { }

    /** The ids of the gated scenarios (key hit/miss paths per §9.1). */
    public static List<String> gatedIds() {
        return all().stream().filter(Scenario::gated).map(Scenario::id).toList();
    }

    /** The ids of the observed-only (not budget-gated) scenarios. */
    public static List<String> observedOnlyIds() {
        return all().stream().filter(s -> !s.gated()).map(Scenario::id).toList();
    }
}
