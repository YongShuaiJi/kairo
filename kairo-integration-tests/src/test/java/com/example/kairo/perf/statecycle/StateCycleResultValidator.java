package com.example.kairo.perf.statecycle;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure schema/content validator for {@code state-cycle-result.json}. Mirrors the
 * {@code ResultValidator} pattern used for {@code benchmark-result.json}: a pure
 * function returning a list of error strings (empty = valid). The harness
 * self-validates with this after writing the file; non-empty errors -> exit 6.
 *
 * <p>A result is valid only when every required scenario ran, the requested/completed
 * counts reconcile, the build id is a 40-hex commit, no hash restoration was skipped,
 * the concurrent conflict resolved without a mixed state, and PR evidence is not
 * dirty. A "fake success" (overall=PASSED with a recorded first failure, a failed
 * scenario, or hashRestored=false) cannot pass.
 */
public final class StateCycleResultValidator {

    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^>]*>|\\.\\.\\.");
    private static final Set<String> CATALOG_IDS = Set.copyOf(StateCycleScenarioCatalog.ids());
    private static final String CONCURRENT_ID = StateCycleScenarioCatalog.concurrentScenario().id();

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
                validateScenario(errors, s, id, overall);
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
        if (cycles.isObject() && cycles.path("requested").isInt() && sumRequested != cycles.path("requested").asInt()) {
            errors.add("sum of scenario cyclesRequested (" + sumRequested
                    + ") must equal cycles.requested (" + cycles.path("requested").asInt() + ")");
        }
        if (cycles.isObject() && cycles.path("completed").isInt() && sumCompleted != cycles.path("completed").asInt()) {
            errors.add("sum of scenario cyclesCompleted (" + sumCompleted
                    + ") must equal cycles.completed (" + cycles.path("completed").asInt() + ")");
        }
        if (cycles.isObject() && cycles.path("failed").isInt() && sumFailed != cycles.path("failed").asInt()) {
            errors.add("sum of scenario cyclesFailed (" + sumFailed
                    + ") must equal cycles.failed (" + cycles.path("failed").asInt() + ")");
        }

        JsonNode conflict = root.path("concurrentConflict");
        if (conflict.isObject()) {
            validateConflict(errors, conflict);
        } else if ("PASSED".equals(overall)) {
            // A passed result must have run and recorded the concurrent conflict.
            errors.add("missing concurrentConflict object");
        }

        JsonNode firstFailure = root.path("firstFailure");
        boolean hasFirstFailure = firstFailure != null && !firstFailure.isNull() && firstFailure.isObject();
        if (!"PASSED".equals(overall) && !"FAILED".equals(overall)) {
            errors.add("overall must be PASSED or FAILED (got: " + overall + ")");
        } else if ("PASSED".equals(overall) && hasFirstFailure) {
            errors.add("overall is PASSED but firstFailure is present (fake success)");
        } else if ("FAILED".equals(overall) && !hasFirstFailure) {
            errors.add("overall is FAILED but firstFailure is absent");
        }

        // Cross-field: a PASSED result must have zero failures everywhere and must
        // have completed every requested cycle.
        if ("PASSED".equals(overall)) {
            if (cycles.isObject() && cycles.path("failed").isInt() && cycles.path("failed").asInt() != 0) {
                errors.add("overall is PASSED but cycles.failed != 0");
            }
            if (cycles.isObject() && cycles.path("completed").isInt() && cycles.path("requested").isInt()
                    && cycles.path("completed").asInt() != cycles.path("requested").asInt()) {
                errors.add("overall is PASSED but cycles.completed != cycles.requested");
            }
            if (scenarios.isArray()) {
                for (JsonNode s : scenarios) {
                    JsonNode f = s.path("cyclesFailed");
                    if (f.isInt() && f.asInt() != 0) {
                        errors.add("overall is PASSED but scenario " + textOrNull(s, "id") + " has cyclesFailed=" + f.asInt());
                    }
                }
            }
        }
        return errors;
    }

    private void validateScenario(List<String> errors, JsonNode s, String id, String overall) {
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
        int completed = comp.isInt() ? comp.asInt() : 0;
        int failed = fail.isInt() ? fail.asInt() : 0;
        int requested = req.isInt() ? req.asInt() : 0;
        if (req.isInt() && comp.isInt() && fail.isInt()
                && completed + failed > requested) {
            errors.add("scenario " + id
                    + ": cyclesCompleted + cyclesFailed must not exceed cyclesRequested");
        }
        // Arithmetic: PASSED requires every scenario to have fully run
        // (completed + failed == requested). A FAILED result legitimately leaves the
        // failing scenario partial (fail-fast) and later scenarios un-run, so the
        // equality is not enforced there (defect 4: partial counts allowed on FAILED).
        if ("PASSED".equals(overall) && req.isInt()
                && completed + failed != requested) {
            errors.add("scenario " + id + ": cyclesCompleted + cyclesFailed != cyclesRequested");
        }
        // firstSample is required for cyclic scenarios that completed at least one cycle.
        // The concurrent scenario's evidence lives in the concurrentConflict block, so it
        // is exempt; if a firstSample is present anyway it is still validated (defect 2).
        if (completed >= 1) {
            JsonNode first = s.path("firstSample");
            if (first.isObject()) {
                validateSample(errors, first, id, "firstSample");
            } else if (!CONCURRENT_ID.equals(id)) {
                errors.add("scenario " + id + ": missing firstSample");
            }
        }
    }

    private void validateSample(List<String> errors, JsonNode sample, String id, String label) {
        requireSha256(errors, sample, "baselineHash", id, label);
        requireSha256(errors, sample, "enhancedHash", id, label);
        requireSha256(errors, sample, "updatedHash", id, label);
        requireSha256(errors, sample, "afterUnloadHash", id, label);
        requireNonBlankText(errors, sample, "enhancedBehavior");
        requireNonBlankText(errors, sample, "updatedBehavior");
        requireNonBlankText(errors, sample, "restoredBehavior");
        requireBool(errors, sample, "enhancedDiffersFromBaseline", id, label);
        requireBool(errors, sample, "normalizedIdentical", id, label);
        requireBool(errors, sample, "hashRestored", id, label);
        requireBool(errors, sample, "rulesClearedAfterUnload", id, label);
        if (!boolOrFalse(sample, "enhancedDiffersFromBaseline")) {
            errors.add("scenario " + id + " " + label + ": enhancedDiffersFromBaseline must be true (non-vacuous)");
        }
        if (!boolOrFalse(sample, "normalizedIdentical")) {
            errors.add("scenario " + id + " " + label + ": normalizedIdentical must be true (hash check skipped)");
        }
        if (!boolOrFalse(sample, "hashRestored")) {
            errors.add("scenario " + id + " " + label + ": hashRestored must be true (hash restoration failed)");
        }
        if (!boolOrFalse(sample, "rulesClearedAfterUnload")) {
            errors.add("scenario " + id + " " + label + ": rulesClearedAfterUnload must be true (rules leaked)");
        }
        String baseline = textOrNull(sample, "baselineHash");
        String enhanced = textOrNull(sample, "enhancedHash");
        String after = textOrNull(sample, "afterUnloadHash");
        if (baseline != null && enhanced != null && baseline.equals(enhanced)) {
            errors.add("scenario " + id + " " + label
                    + ": enhancedHash must differ from baselineHash");
        }
        if (baseline != null && after != null && baseline.equals(after)
                && !boolOrFalse(sample, "hashRestored")) {
            errors.add("scenario " + id + " " + label + ": hashes equal but hashRestored is false (inconsistent)");
        }
    }

    private void validateConflict(List<String> errors, JsonNode conflict) {
        JsonNode threads = conflict.path("threads");
        if (!threads.isInt() || threads.asInt() < 2) {
            errors.add("concurrentConflict.threads must be >= 2");
        }
        JsonNode applied = conflict.path("applied");
        if (!applied.isInt() || applied.asInt() != 1) {
            errors.add("concurrentConflict.applied must be exactly 1 (got " + (applied.isInt() ? applied.asInt() : "non-int") + ")");
        }
        JsonNode stale = conflict.path("staleRejected");
        if (!stale.isInt() || stale.asInt() < 1) {
            errors.add("concurrentConflict.staleRejected must be >= 1");
        }
        // Every competing thread must resolve to APPLIED or STALE_COMMAND: the counts
        // must reconcile to the thread total (applied==1, so staleRejected==threads-1).
        // Catches an unrecognised status leaking into the recorded evidence (defect 3).
        int threadCount = threads.isInt() ? threads.asInt() : -1;
        int appliedCount = applied.isInt() ? applied.asInt() : -1;
        int staleCount = stale.isInt() ? stale.asInt() : -1;
        if (threadCount >= 2 && appliedCount >= 0 && staleCount >= 0
                && appliedCount + staleCount != threadCount) {
            errors.add("concurrentConflict.applied + staleRejected ("
                    + (appliedCount + staleCount) + ") must equal threads (" + threadCount + ")");
        }
        JsonNode mixed = conflict.path("mixedStateDetected");
        if (!mixed.isBoolean() || mixed.asBoolean()) {
            errors.add("concurrentConflict.mixedStateDetected must be false");
        }
        if (!boolOrFalse(conflict, "hashRestored")) {
            errors.add("concurrentConflict.hashRestored must be true");
        }
        if (!boolOrFalse(conflict, "normalizedIdentical")) {
            errors.add("concurrentConflict.normalizedIdentical must be true");
        }
        requireSha256(errors, conflict, "baselineHash", "concurrent-conflict", "conflict");
        requireSha256(errors, conflict, "enhancedHash", "concurrent-conflict", "conflict");
        requireSha256(errors, conflict, "afterUnloadHash", "concurrent-conflict", "conflict");
        String base = textOrNull(conflict, "baselineHash");
        String enhanced = textOrNull(conflict, "enhancedHash");
        String after = textOrNull(conflict, "afterUnloadHash");
        if (base != null && enhanced != null && base.equals(enhanced)) {
            errors.add("concurrentConflict.enhancedHash must differ from baselineHash");
        }
        if (base != null && after != null && !base.equals(after)) {
            errors.add("concurrentConflict.afterUnloadHash must equal baselineHash");
        }
        requireNonBlankText(errors, conflict, "finalBehavior");
        requireNonBlankText(errors, conflict, "restoredBehavior");
        requireNonBlankText(errors, conflict, "winnerRuleId");
    }

    // -------------------------------------------------------- helpers

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        return n.isTextual() ? n.asText() : null;
    }

    private static boolean boolOrFalse(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        return n.isBoolean() && n.asBoolean();
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

    private static void requireSha256(List<String> errors, JsonNode parent, String field, String id, String label) {
        JsonNode n = parent.path(field);
        if (!n.isTextual() || !SHA256.matcher(n.asText()).matches()) {
            errors.add(id + " " + label + ": " + field + " must be a 64-hex SHA-256");
        }
    }

    private static void requireBool(List<String> errors, JsonNode parent, String field, String id, String label) {
        JsonNode n = parent.path(field);
        if (!n.isBoolean()) {
            errors.add(id + " " + label + ": " + field + " must be boolean");
        }
    }
}
