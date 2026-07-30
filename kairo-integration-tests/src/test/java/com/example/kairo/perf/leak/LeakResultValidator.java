package com.example.kairo.perf.leak;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure schema/content validator for {@code leak-result.json}. Mirrors the
 * {@code StateCycleResultValidator} pattern: a pure function returning a list of error
 * strings (empty = valid). The harness self-validates with this after writing the file;
 * non-empty errors -> exit 6.
 *
 * <p>A result is valid only when every required scenario ran, the requested/completed
 * counts reconcile, the build id is a 40-hex commit, the documented budgets are recorded
 * verbatim, the required observation windows (baseline, post-cycles, post-close) are
 * present with all their resource + ClassLoader-bucket + Groovy-diagnostic fields, the
 * warm-up evidence object is present and self-consistent, the gate set is complete, and
 * PR evidence is not dirty. A "fake success" (overall=PASSED with a recorded first
 * failure or a failing gate) cannot pass. Missing or fabricated Groovy/loader fields fail
 * validation (exit 6) so the evidence cannot drift from what the harness actually measured.
 */
public final class LeakResultValidator {

    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^>]*>|\\.\\.\\.");
    private static final Set<String> CATALOG_IDS = Set.copyOf(LeakScenarioCatalog.ids());
    private static final Set<String> REQUIRED_WINDOWS =
            Set.of("baseline", "post-cycles", "post-close");

    /** The complete, ordered gate set the harness must emit. */
    private static final List<String> REQUIRED_GATES = List.of(
            "residual-classloaders", "residual-groovy-loaders",
            "thread-delta", "fd-delta", "heap-growth", "metaspace-growth",
            "rules-cleared", "instrumentation-cleared",
            "snapshot-budget", "journal-budget", "snapshot-cleared-on-close",
            "groovy-cache-budget", "groovy-generation-class-budget", "groovy-cache-cleared-on-close");

    /** Per-observation integer fields. Missing any -> schema failure. */
    private static final List<String> OBS_INT_FIELDS = List.of(
            "publishedRuleCount", "snapshotCount", "journalRecordCount",
            "instrumentationTypeCount", "instrumentationMethodCount",
            "trackedLoadersTotal", "liveTrackedLoaders", "collectedLoaders",
            "measuredBusinessTrackedLoaders", "measuredBusinessLiveTrackedLoaders", "measuredBusinessCollectedLoaders",
            "measuredGroovyTrackedLoaders", "measuredGroovyLiveTrackedLoaders", "measuredGroovyCollectedLoaders",
            "warmupBusinessTrackedLoaders", "warmupBusinessLiveTrackedLoaders", "warmupBusinessCollectedLoaders",
            "warmupGroovyTrackedLoaders", "warmupGroovyLiveTrackedLoaders", "warmupGroovyCollectedLoaders",
            "groovyCacheEntries", "groovyGenerationCount", "groovyMaxClassesInGeneration",
            "groovyGenerationHighWater", "groovyLiveTrackedLoaders");

    public List<String> validate(JsonNode root, int requestedCycles) {
        List<String> errors = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) {
            errors.add("result is null/missing");
            return errors;
        }
        requireText(errors, root, "schemaVersion", "1.0");
        requireNonBlankText(errors, root, "generatedAt");
        requireNonBlankText(errors, root, "startedAt");
        requireNonBlankText(errors, root, "endedAt");

        String buildId = textOrNull(root, "buildId");
        if (buildId == null) {
            errors.add("missing buildId");
        } else if (!HEX40.matcher(buildId).matches()) {
            errors.add("buildId must be a 40-hex lowercase commit id (got: " + buildId + ")");
        }

        String command = textOrNull(root, "command");
        if (command == null || command.isBlank()) {
            errors.add("missing command");
        } else if (PLACEHOLDER.matcher(command).find()) {
            errors.add("command must not contain <...> or ... placeholders");
        }

        JsonNode jvmArgs = root.path("jvmArgs");
        if (!jvmArgs.isArray() || jvmArgs.isEmpty()) {
            errors.add("jvmArgs must be a non-empty array");
        }

        String mode = textOrNull(root, "mode");
        if (!"pr".equals(mode) && !"dev".equals(mode)) {
            errors.add("mode must be 'pr' or 'dev' (got: " + mode + ")");
        }
        JsonNode dirty = root.path("workingTreeDirty");
        if (!dirty.isBoolean()) {
            errors.add("workingTreeDirty must be boolean");
        } else if ("pr".equals(mode) && dirty.asBoolean()) {
            errors.add("PR evidence must not have a dirty working tree");
        }

        JsonNode env = root.path("environment");
        if (!env.isObject()) {
            errors.add("missing environment object");
        } else {
            requireNonBlankText(errors, env, "jdkVersion");
            requireNonBlankText(errors, env, "osName");
            requireNonBlankText(errors, env, "osArch");
            JsonNode procs = env.path("availableProcessors");
            if (!procs.isInt() || procs.asInt() < 1) {
                errors.add("environment.availableProcessors must be >= 1");
            }
        }

        JsonNode cycles = root.path("cycles");
        if (!cycles.isObject()) {
            errors.add("missing cycles object");
        } else {
            JsonNode req = cycles.path("requested");
            JsonNode comp = cycles.path("completed");
            JsonNode fail = cycles.path("failed");
            if (!req.isInt() || req.asInt() != requestedCycles) {
                errors.add("cycles.requested must equal the requested cycle count ("
                        + requestedCycles + ", got " + (req.isInt() ? req.asInt() : "non-int") + ")");
            }
            if (!comp.isInt() || comp.asInt() < 0) {
                errors.add("cycles.completed must be >= 0");
            }
            if (!fail.isInt() || fail.asInt() < 0) {
                errors.add("cycles.failed must be >= 0");
            }
            if (req.isInt() && comp.isInt() && fail.isInt()
                    && comp.asInt() + fail.asInt() > req.asInt()) {
                errors.add("cycles.completed + cycles.failed must not exceed cycles.requested");
            }
        }

        String overall = textOrNull(root, "overall");
        if (!"PASSED".equals(overall) && !"FAILED".equals(overall)) {
            errors.add("overall must be PASSED or FAILED (got: " + overall + ")");
        }

        JsonNode scenarios = root.path("scenarios");
        int sumRequested = 0;
        int sumCompleted = 0;
        int sumFailed = 0;
        if (!scenarios.isArray()) {
            errors.add("scenarios must be an array");
        } else {
            if (scenarios.size() != CATALOG_IDS.size()) {
                errors.add("scenarios must contain exactly " + CATALOG_IDS.size()
                        + " entries (got " + scenarios.size() + ")");
            }
            Set<String> seen = new java.util.HashSet<>();
            for (JsonNode s : scenarios) {
                String id = textOrNull(s, "id");
                if (id == null) {
                    errors.add("scenario entry missing id");
                    continue;
                }
                if (!CATALOG_IDS.contains(id)) {
                    errors.add("unknown scenario id: " + id);
                    continue;
                }
                if (!seen.add(id)) {
                    errors.add("duplicate scenario id: " + id);
                }
                validateScenario(errors, s, id);
                if (s.path("cyclesRequested").isInt()) {
                    sumRequested += s.path("cyclesRequested").asInt();
                }
                if (s.path("cyclesCompleted").isInt()) {
                    sumCompleted += s.path("cyclesCompleted").asInt();
                }
                if (s.path("cyclesFailed").isInt()) {
                    sumFailed += s.path("cyclesFailed").asInt();
                }
            }
            for (String id : CATALOG_IDS) {
                if (!seen.contains(id)) {
                    errors.add("missing scenario: " + id);
                }
            }
        }
        if (cycles.isObject() && cycles.path("requested").isInt()
                && sumRequested != cycles.path("requested").asInt()) {
            errors.add("sum of scenario cyclesRequested (" + sumRequested
                    + ") must equal cycles.requested (" + cycles.path("requested").asInt() + ")");
        }
        if (cycles.isObject() && cycles.path("completed").isInt()
                && sumCompleted != cycles.path("completed").asInt()) {
            errors.add("sum of scenario cyclesCompleted (" + sumCompleted
                    + ") must equal cycles.completed (" + cycles.path("completed").asInt() + ")");
        }
        if (cycles.isObject() && cycles.path("failed").isInt()
                && sumFailed != cycles.path("failed").asInt()) {
            errors.add("sum of scenario cyclesFailed (" + sumFailed
                    + ") must equal cycles.failed (" + cycles.path("failed").asInt() + ")");
        }

        validateObservations(errors, root.path("observations"));
        validateWarmup(errors, root.path("warmup"));
        validateBudgets(errors, root.path("budgets"));
        validateGates(errors, root.path("gates"), overall);
        // Cross-checks that need both observations and gates: the residual gates must
        // reconcile to the post-close bucket sums (warm-up loaders never omitted), and the
        // Groovy generation high-water must be present, monotonic, dominant and reconciled
        // to the generation-class budget gate.
        validateResidualGateReconciliation(errors, root.path("observations"), root.path("gates"));
        validateGroovyHighWater(errors, root.path("observations"), root.path("gates"));

        JsonNode firstFailure = root.path("firstFailure");
        boolean hasFirstFailure = firstFailure != null && !firstFailure.isNull() && firstFailure.isObject();
        if ("PASSED".equals(overall) && hasFirstFailure) {
            errors.add("overall is PASSED but firstFailure is present (fake success)");
        } else if ("FAILED".equals(overall) && !hasFirstFailure) {
            errors.add("overall is FAILED but firstFailure is absent");
        }
        if ("PASSED".equals(overall)) {
            if (cycles.isObject() && cycles.path("failed").isInt() && cycles.path("failed").asInt() != 0) {
                errors.add("overall is PASSED but cycles.failed != 0");
            }
        }
        return errors;
    }

    private void validateScenario(List<String> errors, JsonNode s, String id) {
        JsonNode req = s.path("cyclesRequested");
        if (!req.isInt() || req.asInt() < 1) {
            errors.add("scenario " + id + ": cyclesRequested must be >= 1");
        }
        JsonNode comp = s.path("cyclesCompleted");
        if (!comp.isInt() || comp.asInt() < 0) {
            errors.add("scenario " + id + ": cyclesCompleted must be >= 0");
        }
        JsonNode fail = s.path("cyclesFailed");
        if (!fail.isInt() || fail.asInt() < 0) {
            errors.add("scenario " + id + ": cyclesFailed must be >= 0");
        }
        requireNonBlankText(errors, s, "leakSurface");
        requireNonBlankText(errors, s, "category");
        if (req.isInt() && comp.isInt() && fail.isInt()
                && comp.asInt() + fail.asInt() > req.asInt()) {
            errors.add("scenario " + id
                    + ": cyclesCompleted + cyclesFailed must not exceed cyclesRequested");
        }
    }

    private void validateObservations(List<String> errors, JsonNode observations) {
        if (!observations.isArray() || observations.isEmpty()) {
            errors.add("observations must be a non-empty array");
            return;
        }
        Set<String> labels = new java.util.HashSet<>();
        for (JsonNode o : observations) {
            String label = textOrNull(o, "label");
            if (label == null || label.isBlank()) {
                errors.add("observation entry missing label");
            } else if (!labels.add(label)) {
                errors.add("duplicate observation label: " + label);
            }
            if (!o.path("postFullGc").isBoolean()) {
                errors.add("observation " + label + ": postFullGc must be boolean");
            }
            requireNonBlankText(errors, o, "timestamp");
            requireLong(errors, o, "heapUsedBytes", label);
            requireLong(errors, o, "metaspaceUsedBytes", label);
            if (!o.path("threadCount").isInt()) {
                errors.add("observation " + label + ": threadCount must be int");
            }
            requireLong(errors, o, "openFdCount", label);
            if (!o.path("loadedClassCount").isInt()) {
                errors.add("observation " + label + ": loadedClassCount must be int");
            }
            for (String field : OBS_INT_FIELDS) {
                if (!o.path(field).isInt()) {
                    errors.add("observation " + label + ": " + field + " must be int");
                }
            }
            // The grand-total loader counts must reconcile to the sum of the four
            // business/Groovy × warm-up/measured buckets so a present bucket can never be
            // silently dropped from the totals (§9.3: "212 = 106 + 106").
            validateBucketTotals(errors, o, label);
        }
        for (String required : REQUIRED_WINDOWS) {
            if (!labels.contains(required)) {
                errors.add("missing required observation window: " + required);
            }
        }
    }

    private void validateWarmup(List<String> errors, JsonNode warmup) {
        if (!warmup.isObject()) {
            errors.add("missing warmup object (warm-up evidence is required)");
            return;
        }
        JsonNode paths = warmup.path("exercisedPaths");
        if (!paths.isArray() || paths.isEmpty()) {
            errors.add("warmup.exercisedPaths must be a non-empty array of measured paths");
        } else {
            for (JsonNode p : paths) {
                if (!p.isTextual() || p.asText().isBlank()) {
                    errors.add("warmup.exercisedPaths entries must be non-blank strings");
                    break;
                }
            }
        }
        JsonNode executed = warmup.path("cyclesExecuted");
        if (!executed.isInt() || executed.asInt() < LeakScenarioCatalog.all().size()) {
            errors.add("warmup.cyclesExecuted must be >= the scenario count ("
                    + LeakScenarioCatalog.all().size() + ", got "
                    + (executed.isInt() ? executed.asInt() : "non-int") + ")");
        }
        for (String field : List.of("businessTrackedLoaders", "businessLiveTrackedLoaders",
                "businessCollectedLoaders", "groovyTrackedLoaders", "groovyLiveTrackedLoaders",
                "groovyCollectedLoaders")) {
            if (!warmup.path(field).isInt()) {
                errors.add("warmup." + field + " must be int");
            }
        }
        JsonNode reset = warmup.path("registriesResetToBaseline");
        if (!reset.isBoolean()) {
            errors.add("warmup.registriesResetToBaseline must be boolean");
        } else if (!reset.asBoolean()) {
            // The warm-up contract requires registries to return to the pre-measurement state
            // before the baseline is captured; a false flag means the baseline is not stable.
            errors.add("warmup.registriesResetToBaseline must be true (baseline captured before registries reset)");
        }
    }

    private void validateBudgets(List<String> errors, JsonNode budgets) {
        if (!budgets.isObject()) {
            errors.add("missing budgets object");
            return;
        }
        // The recorded budgets must equal the documented §9.3 values verbatim.
        checkInt(errors, budgets, "maxResidualClassLoaders", LeakBudget.DOCUMENTED.maxResidualClassLoaders());
        checkInt(errors, budgets, "maxThreadDelta", LeakBudget.DOCUMENTED.maxThreadDelta());
        checkInt(errors, budgets, "maxFdDelta", LeakBudget.DOCUMENTED.maxFdDelta());
        checkInt(errors, budgets, "maxHeapGrowthPct", LeakBudget.DOCUMENTED.maxHeapGrowthPct());
        checkInt(errors, budgets, "maxMetaspaceGrowthPct", LeakBudget.DOCUMENTED.maxMetaspaceGrowthPct());
        checkInt(errors, budgets, "snapshotMaxEntries", LeakBudget.DOCUMENTED.snapshotMaxEntries());
        checkInt(errors, budgets, "journalMaxRecords", LeakBudget.DOCUMENTED.journalMaxRecords());
        checkInt(errors, budgets, "groovyCacheMaxEntries", LeakBudget.DOCUMENTED.groovyCacheMaxEntries());
        checkInt(errors, budgets, "generationMaxClasses", LeakBudget.DOCUMENTED.generationMaxClasses());
    }

    private void validateGates(List<String> errors, JsonNode gates, String overall) {
        if (!gates.isArray() || gates.isEmpty()) {
            errors.add("gates must be a non-empty array");
            return;
        }
        boolean anyFailed = false;
        Set<String> names = new java.util.HashSet<>();
        for (JsonNode g : gates) {
            String name = textOrNull(g, "name");
            if (name == null || name.isBlank()) {
                errors.add("gate entry missing name");
                continue;
            }
            if (!names.add(name)) {
                errors.add("duplicate gate name: " + name);
            }
            if (!g.path("passed").isBoolean()) {
                errors.add("gate " + name + ": passed must be boolean");
            } else if (!g.path("passed").asBoolean()) {
                anyFailed = true;
            }
            requireNonBlankText(errors, g, "observed");
            requireNonBlankText(errors, g, "budget");
            requireNonBlankText(errors, g, "detail");
        }
        // Every required gate must be present; a missing gate could hide a failure.
        for (String required : REQUIRED_GATES) {
            if (!names.contains(required)) {
                errors.add("missing required gate: " + required);
            }
        }
        if ("PASSED".equals(overall) && anyFailed) {
            errors.add("overall is PASSED but a gate has passed=false (fake success)");
        }
    }

    /**
     * §9.3: every explicitly created unloadable ClassLoader of a kind is in the residual
     * budget, so the {@code residual-classloaders} / {@code residual-groovy-loaders} gate
     * observed value must reconcile to the post-close measured <em>plus</em> warm-up live
     * counts of that kind. This makes it impossible for warm-up loaders to be present in the
     * evidence yet omitted from the gate.
     */
    private void validateResidualGateReconciliation(List<String> errors, JsonNode observations, JsonNode gates) {
        if (!observations.isArray() || !gates.isArray()) {
            return; // missing arrays already reported
        }
        JsonNode postClose = null;
        for (JsonNode o : observations) {
            if ("post-close".equals(textOrNull(o, "label"))) {
                postClose = o;
                break;
            }
        }
        if (postClose == null) {
            return; // missing post-close already reported
        }
        int mbLive = intOr(postClose, "measuredBusinessLiveTrackedLoaders");
        int wbLive = intOr(postClose, "warmupBusinessLiveTrackedLoaders");
        int mgLive = intOr(postClose, "measuredGroovyLiveTrackedLoaders");
        int wgLive = intOr(postClose, "warmupGroovyLiveTrackedLoaders");
        // Only reconcile when the bucket fields are present; the per-field schema check
        // already flags missing evidence, and a sentinel would otherwise double-report.
        if (mbLive >= 0 && wbLive >= 0) {
            checkResidualGate(errors, gates, "residual-classloaders",
                    mbLive + wbLive, "business", mbLive, wbLive);
        }
        if (mgLive >= 0 && wgLive >= 0) {
            checkResidualGate(errors, gates, "residual-groovy-loaders",
                    mgLive + wgLive, "groovy", mgLive, wgLive);
        }
    }

    private void checkResidualGate(List<String> errors, JsonNode gates, String gateName,
                                   int expectedSum, String kind, int measuredLive, int warmupLive) {
        JsonNode gate = findGateByName(gates, gateName);
        if (gate == null) {
            return; // missing gate already reported
        }
        JsonNode observed = gate.path("observed");
        if (!observed.isTextual()) {
            errors.add("gate " + gateName + ": observed must be a string");
            return;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(observed.asText().trim());
        } catch (NumberFormatException e) {
            errors.add("gate " + gateName + ": observed must be an integer (got: " + observed.asText() + ")");
            return;
        }
        if (parsed != expectedSum) {
            errors.add("gate " + gateName + ": observed (" + parsed + ") must equal measured " + kind
                    + " live (" + measuredLive + ") + warm-up " + kind + " live (" + warmupLive
                    + ") = " + expectedSum
                    + " (§9.3: warm-up loaders must never be omitted from the residual budget)");
        }
    }

    /**
     * The Groovy generation high-water is a run-scoped, monotonic measurement. It must be
     * present in every observation, non-decreasing across observations (it is one probe
     * counter), dominate the point-in-time {@code groovyMaxClassesInGeneration} in the same
     * window (the high-water is folded from that very measurement), and the
     * {@code groovy-generation-class-budget} gate must use it. A missing or inconsistent
     * value is rejected so the gate can never report a fabricated zero.
     */
    private void validateGroovyHighWater(List<String> errors, JsonNode observations, JsonNode gates) {
        if (!observations.isArray()) {
            return;
        }
        int prev = -1;
        boolean seen = false;
        int runMax = 0;
        for (JsonNode o : observations) {
            String label = textOrNull(o, "label");
            JsonNode hw = o.path("groovyGenerationHighWater");
            if (!hw.isInt()) {
                continue; // missing already reported as a schema error
            }
            int v = hw.asInt();
            if (v < 0) {
                errors.add("observation " + label + ": groovyGenerationHighWater must be >= 0 (got " + v + ")");
            }
            JsonNode mc = o.path("groovyMaxClassesInGeneration");
            if (mc.isInt() && v < mc.asInt()) {
                errors.add("observation " + label + ": groovyGenerationHighWater (" + v
                        + ") must be >= groovyMaxClassesInGeneration (" + mc.asInt()
                        + ") (the run-scoped high-water must dominate the point-in-time max)");
            }
            if (seen && v < prev) {
                errors.add("observation " + label + ": groovyGenerationHighWater (" + v
                        + ") must be non-decreasing across observations (previous=" + prev
                        + "); the run-scoped high-water is monotonic");
            }
            prev = v;
            seen = true;
            if (v > runMax) {
                runMax = v;
            }
        }
        // The generation-class budget gate must use the run high-water across the run.
        JsonNode gate = findGateByName(gates, "groovy-generation-class-budget");
        if (gate != null && seen) {
            JsonNode observed = gate.path("observed");
            int parsed;
            try {
                parsed = Integer.parseInt(observed.asText().trim());
            } catch (Exception e) {
                errors.add("gate groovy-generation-class-budget: observed must be an integer (got: "
                        + (observed.isTextual() ? observed.asText() : "missing") + ")");
                return;
            }
            if (parsed != runMax) {
                errors.add("gate groovy-generation-class-budget: observed (" + parsed
                        + ") must equal the max groovyGenerationHighWater across observations ("
                        + runMax + ") (the gate must use the run-scoped high-water)");
            }
        }
    }

    /** The grand-total loader counts must reconcile to the sum of the four buckets. */
    private void validateBucketTotals(List<String> errors, JsonNode o, String label) {
        int mbt = intOr(o, "measuredBusinessTrackedLoaders");
        int mgt = intOr(o, "measuredGroovyTrackedLoaders");
        int wbt = intOr(o, "warmupBusinessTrackedLoaders");
        int wgt = intOr(o, "warmupGroovyTrackedLoaders");
        int ttl = intOr(o, "trackedLoadersTotal");
        int mbl = intOr(o, "measuredBusinessLiveTrackedLoaders");
        int mgl = intOr(o, "measuredGroovyLiveTrackedLoaders");
        int wbl = intOr(o, "warmupBusinessLiveTrackedLoaders");
        int wgl = intOr(o, "warmupGroovyLiveTrackedLoaders");
        int ttlLive = intOr(o, "liveTrackedLoaders");
        int mbc = intOr(o, "measuredBusinessCollectedLoaders");
        int mgc = intOr(o, "measuredGroovyCollectedLoaders");
        int wbc = intOr(o, "warmupBusinessCollectedLoaders");
        int wgc = intOr(o, "warmupGroovyCollectedLoaders");
        int ttlCollected = intOr(o, "collectedLoaders");
        if (mbt >= 0 && mgt >= 0 && wbt >= 0 && wgt >= 0 && ttl >= 0 && ttl != mbt + mgt + wbt + wgt) {
            errors.add("observation " + label + ": trackedLoadersTotal (" + ttl
                    + ") must equal the sum of the four bucket tracked counts ("
                    + mbt + "+" + mgt + "+" + wbt + "+" + wgt + "=" + (mbt + mgt + wbt + wgt) + ")");
        }
        if (mbl >= 0 && mgl >= 0 && wbl >= 0 && wgl >= 0 && ttlLive >= 0
                && ttlLive != mbl + mgl + wbl + wgl) {
            errors.add("observation " + label + ": liveTrackedLoaders (" + ttlLive
                    + ") must equal the sum of the four bucket live counts ("
                    + mbl + "+" + mgl + "+" + wbl + "+" + wgl + "=" + (mbl + mgl + wbl + wgl) + ")");
        }
        if (mbc >= 0 && mgc >= 0 && wbc >= 0 && wgc >= 0 && ttlCollected >= 0
                && ttlCollected != mbc + mgc + wbc + wgc) {
            errors.add("observation " + label + ": collectedLoaders (" + ttlCollected
                    + ") must equal the sum of the four bucket collected counts ("
                    + mbc + "+" + mgc + "+" + wbc + "+" + wgc + "=" + (mbc + mgc + wbc + wgc) + ")");
        }
    }

    // -------------------------------------------------------- helpers

    private static JsonNode findGateByName(JsonNode gates, String name) {
        if (!gates.isArray()) {
            return null;
        }
        for (JsonNode g : gates) {
            if (name.equals(textOrNull(g, "name"))) {
                return g;
            }
        }
        return null;
    }

    /** An int field's value, or -1 when absent/non-int (counts are never negative). */
    private static int intOr(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        return n.isInt() ? n.asInt() : -1;
    }

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        return n.isTextual() ? n.asText() : null;
    }

    private static void requireText(List<String> errors, JsonNode parent, String field, String expected) {
        JsonNode n = parent.path(field);
        if (!n.isTextual() || !expected.equals(n.asText())) {
            errors.add(field + " must equal '" + expected + "' (got: " + (n.isTextual() ? n.asText() : "missing") + ")");
        }
    }

    private static void requireNonBlankText(List<String> errors, JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (!n.isTextual() || n.asText().isBlank()) {
            errors.add(field + " must be a non-blank string");
        }
    }

    private static void requireLong(List<String> errors, JsonNode parent, String field, String label) {
        JsonNode n = parent.path(field);
        if (!n.isIntegralNumber() && !n.isTextual()) {
            errors.add("observation " + label + ": " + field + " must be a number");
        }
    }

    private static void checkInt(List<String> errors, JsonNode parent, String field, int expected) {
        JsonNode n = parent.path(field);
        if (!n.isInt() || n.asInt() != expected) {
            errors.add("budgets." + field + " must equal the documented value " + expected
                    + " (got: " + (n.isInt() ? n.asInt() : "missing") + ")");
        }
    }
}
