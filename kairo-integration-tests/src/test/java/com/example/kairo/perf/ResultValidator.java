package com.example.kairo.perf;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the structure and content of a generated {@code benchmark-result.json}.
 * Pure function over a {@link JsonNode}; no I/O. Returns a list of error strings —
 * empty means valid. Unit-tested with fixture JSON (see {@code ResultValidatorTest}).
 *
 * <p>Checks:
 * <ul>
 *   <li>exact schema version;</li>
 *   <li>required top-level fields, environment metadata, captured JVM args;</li>
 *   <li>resolved build IDs are 40-hex commit IDs (peeled from any annotated tag);</li>
 *   <li>build commands are exact (no {@code <...>} placeholders), with structured
 *       buildCommand/harnessCommand/classpath fields;</li>
 *   <li>PR mode has &ge;5 forks, &ge;configured samples per scenario, and
 *       candidateWorkingTreeDirty=false; smoke may be dirty;</li>
 *   <li>scenario completeness against the catalog and per-scenario stats shape:
 *       every scenario must have positive finite median/P95/P99/stddev/dispersion
 *       and a positive sample count on BOTH sides (all 14 are mandatory);</li>
 *   <li>exact per-side raw fork count is represented and consistent;</li>
 *   <li>budget verdict is one of {PASS, FAIL, NOT_COMPARABLE, NOT_GATED};</li>
 *   <li>budget passed state is consistent with failed AND non-comparable gated scenarios.</li>
 * </ul>
 */
public final class ResultValidator {

    private static final Set<String> VERDICTS =
            Set.of("PASS", "FAIL", "NOT_COMPARABLE", "NOT_GATED");
    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^>]*>|\\.\\.\\.");
    private static final String EXPECTED_SCHEMA = "1.0";
    private static final int PR_MIN_FORKS = 5;

    /** Validate the result against the full catalog of expected scenario ids. */
    public List<String> validate(JsonNode root, List<String> catalogIds) {
        List<String> errors = new ArrayList<>();
        if (root == null || root.isNull()) {
            errors.add("result is null");
            return errors;
        }

        // Exact schema version.
        JsonNode sv = root.get("schemaVersion");
        if (sv == null || sv.isNull() || !EXPECTED_SCHEMA.equals(sv.asText())) {
            errors.add("schemaVersion must be exactly '" + EXPECTED_SCHEMA + "': "
                    + (sv == null ? "missing" : sv.asText()));
        }

        String mode = requireText(root, "mode", errors);
        if (mode != null && !Set.of("pr", "smoke").contains(mode)) {
            errors.add("mode must be 'pr' or 'smoke': " + mode);
        }
        requireText(root, "budgetFile", errors);
        requireText(root, "budgetDirection", errors);
        requireText(root, "units", errors);
        requireText(root, "metricDirection", errors);

        JsonNode env = root.get("environment");
        if (env == null || env.isNull()) {
            errors.add("missing environment");
        } else {
            requireText(env, "jdkVersion", errors);
            requireText(env, "osName", errors);
            requireText(env, "osArch", errors);
            int procs = env.path("availableProcessors").asInt(-1);
            if (procs < 1) {
                errors.add("environment.availableProcessors must be >= 1: " + procs);
            }
        }

        JsonNode jvmArgs = root.get("jvmArgs");
        if (jvmArgs == null || !jvmArgs.isArray() || jvmArgs.isEmpty()) {
            errors.add("jvmArgs must be a non-empty array");
        }

        // Harness metadata + PR-mode constraints.
        JsonNode harness = root.get("harness");
        int forks = -1;
        int measureIters = -1;
        boolean candDirty = false;
        if (harness == null || !harness.isObject()) {
            errors.add("missing harness metadata");
        } else {
            forks = harness.path("forks").asInt(-1);
            measureIters = harness.path("measurementIterations").asInt(-1);
            candDirty = harness.path("candidateWorkingTreeDirty").asBoolean(false);
            if (forks < 1) {
                errors.add("harness.forks must be >= 1: " + forks);
            }
            if (measureIters < 1) {
                errors.add("harness.measurementIterations must be >= 1: " + measureIters);
            }
            if ("pr".equals(mode)) {
                if (forks < PR_MIN_FORKS) {
                    errors.add("PR mode requires >= " + PR_MIN_FORKS + " forks: " + forks);
                }
                if (candDirty) {
                    errors.add("PR mode candidateWorkingTreeDirty must be false (uncommitted code "
                            + "must not be bound to HEAD PR evidence)");
                }
            }
        }

        // Builds: resolved build IDs must be 40-hex commit IDs; commands must be exact.
        JsonNode builds = root.get("builds");
        if (builds == null || builds.isNull()) {
            errors.add("missing builds");
        } else {
            String baseId = validateBuild(builds, "baseline", errors);
            String candId = validateBuild(builds, "candidate", errors);
            if (baseId != null && !HEX40.matcher(baseId).matches()) {
                errors.add("builds.baseline.resolvedBuildId must be a 40-hex commit id: " + baseId);
            }
            if (candId != null && !HEX40.matcher(candId).matches()) {
                errors.add("builds.candidate.resolvedBuildId must be a 40-hex commit id: " + candId);
            }
        }

        JsonNode scenarios = root.get("scenarios");
        if (scenarios == null || !scenarios.isArray()) {
            errors.add("scenarios must be an array");
            return errors;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode sc : scenarios) {
            String id = sc.path("id").asText("");
            if (id.isEmpty()) {
                errors.add("scenario missing id");
                continue;
            }
            if (!catalogIds.contains(id)) {
                errors.add("scenario id not in catalog: " + id);
            }
            if (!seen.add(id)) {
                errors.add("duplicate scenario id: " + id);
            }
            // Every scenario is mandatory: BOTH sides must have valid positive-finite stats.
            int baseForks = validateScenarioStats(sc, "baseline", errors, id);
            int candForks = validateScenarioStats(sc, "candidate", errors, id);
            // Fork-count consistency: per-side fork count must be positive and (when known)
            // equal to the harness fork count.
            if (baseForks < 1) {
                errors.add("scenario " + id + " baseline forkCount < 1");
            }
            if (candForks < 1) {
                errors.add("scenario " + id + " candidate forkCount < 1");
            }
            if (forks >= 1 && baseForks >= 1 && baseForks != forks) {
                errors.add("scenario " + id + " baseline forkCount=" + baseForks
                        + " != harness.forks=" + forks);
            }
            if (forks >= 1 && candForks >= 1 && candForks != forks) {
                errors.add("scenario " + id + " candidate forkCount=" + candForks
                        + " != harness.forks=" + forks);
            }
            // Every fork must contribute exactly measurementIterations samples.
            if (forks >= 1 && measureIters >= 1) {
                int bs = sc.path("baseline").path("sampleCount").asInt(0);
                int cs = sc.path("candidate").path("sampleCount").asInt(0);
                long expectedSamples = (long) forks * measureIters;
                if (bs > 0 && bs != expectedSamples) {
                    errors.add("scenario " + id + " baseline sampleCount=" + bs
                            + " != expected forks*measurementIterations=" + expectedSamples);
                }
                if (cs > 0 && cs != expectedSamples) {
                    errors.add("scenario " + id + " candidate sampleCount=" + cs
                            + " != expected forks*measurementIterations=" + expectedSamples);
                }
            }
            JsonNode cmp = sc.get("comparison");
            if (cmp == null || cmp.isNull()) {
                errors.add("scenario " + id + " missing comparison");
            } else {
                String v = cmp.path("verdict").asText("");
                if (!VERDICTS.contains(v)) {
                    errors.add("scenario " + id + " comparison.verdict invalid: " + v);
                }
                JsonNode gatedNode = cmp.get("gated");
                if (gatedNode == null || !gatedNode.isBoolean()) {
                    errors.add("scenario " + id + " comparison.gated must be boolean");
                }
            }
        }
        // Completeness: every catalog id must appear.
        for (String id : catalogIds) {
            if (!seen.contains(id)) {
                errors.add("missing scenario in result: " + id);
            }
        }

        // Budget consistency.
        JsonNode budget = root.get("budget");
        if (budget == null || budget.isNull()) {
            errors.add("missing budget");
        } else {
            boolean passed = budget.path("passed").asBoolean(false);
            JsonNode failed = budget.path("failedScenarios");
            JsonNode nonCompGated = budget.path("nonComparableGatedScenarios");
            if (!failed.isArray()) {
                errors.add("budget.failedScenarios must be an array");
            }
            if (!nonCompGated.isArray()) {
                errors.add("budget.nonComparableGatedScenarios must be an array");
            }
            if (passed && !failed.isEmpty()) {
                errors.add("budget.passed=true but failedScenarios is non-empty");
            }
            if (passed && !nonCompGated.isEmpty()) {
                errors.add("budget.passed=true but nonComparableGatedScenarios is non-empty "
                        + "(a gated NOT_COMPARABLE scenario must fail the budget)");
            }
        }
        return errors;
    }

    private String validateBuild(JsonNode builds, String key, List<String> errors) {
        JsonNode b = builds.get(key);
        if (b == null || b.isNull()) {
            errors.add("missing builds." + key);
            return null;
        }
        requireText(b, "resolvedBuildId", errors);
        requireText(b, "sourceRef", errors);
        // Structured, exact commands — no placeholders.
        String buildCmd = requireText(b, "buildCommand", errors);
        String harnessCmd = requireText(b, "harnessCommand", errors);
        requireText(b, "classpath", errors);
        if (buildCmd != null && PLACEHOLDER.matcher(buildCmd).find()) {
            errors.add("builds." + key + ".buildCommand must not contain placeholders: " + buildCmd);
        }
        if (harnessCmd != null && PLACEHOLDER.matcher(harnessCmd).find()) {
            errors.add("builds." + key + ".harnessCommand must not contain placeholders: " + harnessCmd);
        }
        return b.path("resolvedBuildId").asText(null);
    }

    /**
     * Validate one side's stats. Every scenario is mandatory, so missing stats or
     * non-positive/non-finite values are errors. Returns the fork count (or -1).
     */
    private int validateScenarioStats(JsonNode sc, String side, List<String> errors, String id) {
        JsonNode stats = sc.get(side);
        if (stats == null || stats.isNull()) {
            errors.add("scenario " + id + " missing " + side + " stats");
            return -1;
        }
        int sampleCount = stats.path("sampleCount").asInt(0);
        if (sampleCount < 1) {
            errors.add("scenario " + id + " " + side + " sampleCount < 1");
        }
        if (sampleCount >= 1) {
            requirePositiveFinite(stats, "median", id, side, errors);
            requirePositiveFinite(stats, "p95", id, side, errors);
            requirePositiveFinite(stats, "p99", id, side, errors);
            requireNonNegativeFinite(stats, "stddev", id, side, errors);
            requireNonNegativeFinite(stats, "dispersion", id, side, errors);
            requirePositiveFinite(stats, "mean", id, side, errors);
        }
        int forkCount = stats.path("forkCount").asInt(-1);
        return forkCount;
    }

    private void requirePositiveFinite(JsonNode stats, String field, String id, String side, List<String> errors) {
        JsonNode n = stats.get(field);
        if (n == null || !n.isNumber()) {
            errors.add("scenario " + id + " " + side + " " + field + " missing");
            return;
        }
        double v = n.asDouble();
        if (!(v > 0.0) || Double.isNaN(v) || Double.isInfinite(v)) {
            errors.add("scenario " + id + " " + side + " " + field + " must be positive finite: " + v);
        }
    }

    private void requireNonNegativeFinite(JsonNode stats, String field, String id, String side, List<String> errors) {
        JsonNode n = stats.get(field);
        if (n == null || !n.isNumber()) {
            errors.add("scenario " + id + " " + side + " " + field + " missing");
            return;
        }
        double v = n.asDouble();
        if (v < 0.0 || Double.isNaN(v) || Double.isInfinite(v)) {
            errors.add("scenario " + id + " " + side + " " + field + " must be non-negative finite: " + v);
        }
    }

    private static String requireText(JsonNode node, String field, List<String> errors) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || child.asText().isEmpty()) {
            errors.add("missing/empty field: " + field);
            return null;
        }
        return child.asText();
    }
}
