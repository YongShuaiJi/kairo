package com.example.kairo.perf.soak;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure schema/content validator for {@code soak-result.json}. Mirrors the
 * {@code LeakResultValidator} / {@code StateCycleResultValidator} pattern: a pure function
 * returning a list of error strings (empty = valid). The harness self-validates with this
 * after writing the file; non-empty errors -> exit 6.
 *
 * <p>A result is valid only when: the fixed M2-D cadence (PT1M / PT5M / PT30M) is recorded
 * verbatim; the documented budgets are recorded verbatim; the requested duration reconciles;
 * build id is a 40-hex commit; the command is non-placeholder; the environment, JVM args,
 * duration, cycles, time-series (with an in-repo raw path) and disconnect/recovery blocks are
 * present and well-typed; the per-minute observations are non-empty and reconcile to the
 * time-series count; and a "fake success" (overall=PASSED with a recorded first failure, a
 * non-completed duration, or a non-zero failed-batch count) cannot pass. PR evidence that is
 * dirty cannot pass.
 */
public final class SoakResultValidator {

    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^>]*>|\\.\\.\\.");

    public List<String> validate(JsonNode root, Duration requestedDuration) {
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

        String overall = textOrNull(root, "overall");
        boolean passing = "PASSED".equals(overall);
        validateEnvironment(errors, root.path("environment"));
        validateDuration(errors, root.path("duration"), requestedDuration, passing);
        validateCadence(errors, root.path("cadence"));
        validateWorkloadTopology(errors, root.path("workloadTopology"));
        validateMeasurementWarmup(errors, root.path("measurementWarmup"), passing);
        validateBudgets(errors, root.path("budgets"));
        validateCycles(errors, root.path("cycles"), requestedDuration, passing);
        validateTimeSeries(errors, root.path("timeSeries"), root.path("observations"),
                requestedDuration, passing);
        validateDisconnectRecovery(errors, root.path("disconnectRecovery"), root.path("cycles"),
                requestedDuration, passing);

        JsonNode oom = root.path("oomEvidence");
        if (!oom.isBoolean()) {
            errors.add("oomEvidence must be boolean");
        }

        if (!"PASSED".equals(overall) && !"FAILED".equals(overall)) {
            errors.add("overall must be PASSED or FAILED (got: " + overall + ")");
        }
        String finalState = textOrNull(root, "finalState");
        if (!Set.of("COMPLETED", "FAILED", "ABORTED").contains(finalState)) {
            errors.add("finalState must be COMPLETED, FAILED or ABORTED (got: " + finalState + ")");
        }

        JsonNode firstFailure = root.path("firstFailure");
        boolean hasFirstFailure = firstFailure != null && !firstFailure.isNull() && firstFailure.isObject();
        if ("PASSED".equals(overall) && hasFirstFailure) {
            errors.add("overall is PASSED but firstFailure is present (fake success)");
        } else if ("FAILED".equals(overall) && !hasFirstFailure) {
            errors.add("overall is FAILED but firstFailure is absent");
        }
        if (hasFirstFailure) {
            requireNonBlankText(errors, firstFailure, "phase");
            requireNonBlankText(errors, firstFailure, "reason");
            requireNonBlankText(errors, firstFailure, "failureTime");
        }
        // A passing run must have completed the full requested duration and have no failed batches.
        if ("PASSED".equals(overall)) {
            if (!"COMPLETED".equals(finalState)) {
                errors.add("overall is PASSED but finalState is not COMPLETED");
            }
            if (oom.isBoolean() && oom.asBoolean()) {
                errors.add("overall is PASSED but oomEvidence is true");
            }
            JsonNode dur = root.path("duration");
            if (dur.isObject() && dur.path("completed").isBoolean() && !dur.path("completed").asBoolean()) {
                errors.add("overall is PASSED but duration.completed is false (did not run the full duration)");
            }
            JsonNode cyc = root.path("cycles");
            if (cyc.isObject() && cyc.path("failedBatches").isInt() && cyc.path("failedBatches").asInt() != 0) {
                errors.add("overall is PASSED but cycles.failedBatches != 0");
            }
            JsonNode dr = root.path("disconnectRecovery");
            if (dr.isObject() && dr.path("count").isInt() && dr.path("count").asInt() > 0
                    && dr.path("lastOutcome").isTextual()
                    && !"RECOVERED".equals(dr.path("lastOutcome").asText())) {
                // A passing run that actually ran a disconnect/recovery must have recovered.
                // (A run shorter than the disconnect cadence has count=0 and lastOutcome=NONE.)
                errors.add("overall is PASSED but disconnectRecovery.lastOutcome != RECOVERED"
                        + " (count=" + dr.path("count").asInt() + ")");
            }
        }
        return errors;
    }

    private void validateEnvironment(List<String> errors, JsonNode env) {
        if (!env.isObject()) {
            errors.add("missing environment object");
            return;
        }
        requireNonBlankText(errors, env, "jdkVersion");
        requireNonBlankText(errors, env, "osName");
        requireNonBlankText(errors, env, "osArch");
        JsonNode procs = env.path("availableProcessors");
        if (!procs.isInt() || procs.asInt() < 1) {
            errors.add("environment.availableProcessors must be >= 1");
        }
    }

    private void validateDuration(List<String> errors, JsonNode dur, Duration requestedDuration,
                                  boolean passing) {
        if (!dur.isObject()) {
            errors.add("missing duration object");
            return;
        }
        String requested = textOrNull(dur, "requested");
        if (requested == null) {
            errors.add("duration.requested is required (ISO-8601)");
        } else if (!requested.equals(requestedDuration.toString())) {
            errors.add("duration.requested (" + requested + ") must equal the requested duration ("
                    + requestedDuration + ")");
        }
        JsonNode reqSec = dur.path("requestedSeconds");
        if (!reqSec.isNumber() || reqSec.asLong() != requestedDuration.toSeconds()) {
            errors.add("duration.requestedSeconds must equal the requested duration in seconds ("
                    + requestedDuration.toSeconds() + ")");
        }
        if (!dur.path("completedSeconds").isNumber() || dur.path("completedSeconds").asDouble() < 0) {
            errors.add("duration.completedSeconds must be a non-negative number");
        } else if (passing && dur.path("completedSeconds").asDouble() < requestedDuration.toMillis() / 1000.0) {
            errors.add("overall is PASSED but duration.completedSeconds is shorter than requested duration");
        }
        requireNonBlankText(errors, dur, "completedIso");
        if (!dur.path("completed").isBoolean()) {
            errors.add("duration.completed must be boolean");
        }
    }

    private void validateCadence(List<String> errors, JsonNode cadence) {
        if (!cadence.isObject()) {
            errors.add("missing cadence object (fixed M2-D cadence is required)");
            return;
        }
        // §9.4: the fixed cadence must remain explicit and verified verbatim.
        requireText(errors, cadence, "summaryInterval", SoakCadence.DOCUMENTED.summaryInterval().toString());
        requireText(errors, cadence, "batchInterval", SoakCadence.DOCUMENTED.batchInterval().toString());
        requireText(errors, cadence, "disconnectInterval", SoakCadence.DOCUMENTED.disconnectInterval().toString());
    }

    private void validateBudgets(List<String> errors, JsonNode budgets) {
        if (!budgets.isObject()) {
            errors.add("missing budgets object");
            return;
        }
        checkInt(errors, budgets, "maxHeapGrowthPct", SoakBudget.DOCUMENTED.maxHeapGrowthPct());
        checkInt(errors, budgets, "maxMetaspaceGrowthPct", SoakBudget.DOCUMENTED.maxMetaspaceGrowthPct());
        checkInt(errors, budgets, "maxThreadDelta", SoakBudget.DOCUMENTED.maxThreadDelta());
        checkInt(errors, budgets, "maxFdDelta", SoakBudget.DOCUMENTED.maxFdDelta());
        checkInt(errors, budgets, "driftThresholdSeconds", SoakBudget.DOCUMENTED.driftThresholdSeconds());
        checkInt(errors, budgets, "sustainedBreachWindowSeconds", SoakBudget.DOCUMENTED.sustainedBreachWindowSeconds());
    }

    private void validateWorkloadTopology(List<String> errors, JsonNode topology) {
        if (!topology.isObject()) {
            errors.add("missing workloadTopology object");
            return;
        }
        String continuous = textOrNull(topology, "continuousTargetClass");
        String lifecycle = textOrNull(topology, "lifecycleTargetClass");
        if (continuous == null || continuous.isBlank()) {
            errors.add("workloadTopology.continuousTargetClass must be non-blank");
        }
        if (lifecycle == null || lifecycle.isBlank()) {
            errors.add("workloadTopology.lifecycleTargetClass must be non-blank");
        }
        if (continuous != null && continuous.equals(lifecycle)) {
            errors.add("continuous and lifecycle targets must be different classes");
        }
        requireBoolean(errors, topology, "classSeparated", true);
        requireBoolean(errors, topology, "lifecycleClassLoaderPerBatch", true);
        requireBoolean(errors, topology, "continuousTargetParticipatesInLifecycleBatches", false);
        requireBoolean(errors, topology, "lifecycleTargetReceivesContinuousTraffic", false);
    }

    private void validateMeasurementWarmup(List<String> errors, JsonNode warmup, boolean passing) {
        if (!warmup.isObject()) {
            errors.add("missing measurementWarmup object");
            return;
        }
        requireText(errors, warmup, "strategy", "bounded-adaptive-metaspace-plateau");
        requireBoolean(errors, warmup, "excludedFromDurationAndCycles", true);
        for (String field : List.of("enhanceUnloadBatch", "disconnectRecovery", "resourceSample",
                "steadyStateEstablished")) {
            if (!warmup.path(field).isBoolean()) {
                errors.add("measurementWarmup." + field + " must be boolean");
            }
        }
        for (String field : List.of("minimumLifecycleBatches", "maximumLifecycleBatches",
                "sampleEveryBatches", "plateauWindowBatches", "batchesRun",
                "lifecycleLoadersCreated", "lifecycleLoadersCollected",
                "lifecycleLoadersOutstanding", "eligibleLifecycleLoaders",
                "eligibleLifecycleLoadersOutstanding", "latestCohortGraceLoaders",
                "allowedOutstandingLifecycleLoaders")) {
            if (!warmup.path(field).isInt() || warmup.path(field).asInt() < 0) {
                errors.add("measurementWarmup." + field + " must be a non-negative int");
            }
        }
        for (String field : List.of("initialMetaspaceUsedBytes", "finalMetaspaceUsedBytes")) {
            if (!warmup.path(field).isIntegralNumber()) {
                errors.add("measurementWarmup." + field + " must be an integer");
            }
        }
        if (!warmup.path("maxWindowMetaspaceGrowthPct").isNumber()
                || warmup.path("maxWindowMetaspaceGrowthPct").asDouble() < 0) {
            errors.add("measurementWarmup.maxWindowMetaspaceGrowthPct must be a non-negative number");
        }
        if (!warmup.path("observedWindowMetaspaceGrowthPct").isNumber()
                || !Double.isFinite(warmup.path("observedWindowMetaspaceGrowthPct").asDouble())) {
            errors.add("measurementWarmup.observedWindowMetaspaceGrowthPct must be finite");
        }
        JsonNode samples = warmup.path("samples");
        if (!samples.isArray()) {
            errors.add("measurementWarmup.samples must be an array");
        }
        if (passing) {
            requireBoolean(errors, warmup, "enhanceUnloadBatch", true);
            requireBoolean(errors, warmup, "disconnectRecovery", true);
            requireBoolean(errors, warmup, "resourceSample", true);
            requireBoolean(errors, warmup, "steadyStateEstablished", true);
            int batches = warmup.path("batchesRun").asInt(-1);
            int minimum = warmup.path("minimumLifecycleBatches").asInt(Integer.MAX_VALUE);
            int maximum = warmup.path("maximumLifecycleBatches").asInt(-1);
            if (batches < minimum || batches > maximum) {
                errors.add("passing measurementWarmup.batchesRun must be within its bounded calibration range");
            }
            double observed = warmup.path("observedWindowMetaspaceGrowthPct").asDouble(Double.POSITIVE_INFINITY);
            double allowed = warmup.path("maxWindowMetaspaceGrowthPct").asDouble(-1.0);
            if (observed > allowed) {
                errors.add("passing measurementWarmup observed Metaspace growth exceeds plateau limit");
            }
            int outstanding = warmup.path("eligibleLifecycleLoadersOutstanding").asInt(Integer.MAX_VALUE);
            int allowedOutstanding = warmup.path("allowedOutstandingLifecycleLoaders").asInt(-1);
            if (outstanding > allowedOutstanding) {
                errors.add("passing measurementWarmup has unreclaimed lifecycle ClassLoaders");
            }
            int created = warmup.path("lifecycleLoadersCreated").asInt(-1);
            int collected = warmup.path("lifecycleLoadersCollected").asInt(-1);
            int totalOutstanding = warmup.path("lifecycleLoadersOutstanding").asInt(-1);
            int eligible = warmup.path("eligibleLifecycleLoaders").asInt(-1);
            int grace = warmup.path("latestCohortGraceLoaders").asInt(-1);
            if (created < collected || totalOutstanding != created - collected) {
                errors.add("passing measurementWarmup lifecycle ClassLoader totals do not reconcile");
            }
            if (eligible < 0 || eligible > created || grace != created - eligible) {
                errors.add("passing measurementWarmup eligible/grace ClassLoader cohorts do not reconcile");
            }
            if (warmup.path("latestCohortGraceLoaders").asInt(Integer.MAX_VALUE)
                    > warmup.path("sampleEveryBatches").asInt(-1)) {
                errors.add("passing measurementWarmup ClassLoader grace cohort exceeds one sample interval");
            }
            if (!samples.isArray() || samples.size() < 2) {
                errors.add("passing measurementWarmup.samples must contain plateau evidence");
            }
        }
    }

    private void validateCycles(List<String> errors, JsonNode cycles, Duration requestedDuration,
                                boolean passing) {
        if (!cycles.isObject()) {
            errors.add("missing cycles object");
            return;
        }
        if (!cycles.path("continuousInvocations").isIntegralNumber() || cycles.path("continuousInvocations").asLong() < 0) {
            errors.add("cycles.continuousInvocations must be a non-negative integer");
        }
        if (!cycles.path("continuousTargetEnhanceApplications").isInt()
                || cycles.path("continuousTargetEnhanceApplications").asInt() < 0) {
            errors.add("cycles.continuousTargetEnhanceApplications must be a non-negative integer");
        }
        if (!cycles.path("enhanceUnloadBatches").isInt() || cycles.path("enhanceUnloadBatches").asInt() < 0) {
            errors.add("cycles.enhanceUnloadBatches must be >= 0");
        }
        if (!cycles.path("disconnectRecoveries").isInt() || cycles.path("disconnectRecoveries").asInt() < 0) {
            errors.add("cycles.disconnectRecoveries must be >= 0");
        }
        if (!cycles.path("summaries").isInt()
                || cycles.path("summaries").asInt() < (passing ? 1 : 0)) {
            errors.add("cycles.summaries must be >= " + (passing ? 1 : 0));
        }
        if (!cycles.path("failedBatches").isInt() || cycles.path("failedBatches").asInt() < 0) {
            errors.add("cycles.failedBatches must be >= 0");
        }
        if (passing) {
            long seconds = requestedDuration.toSeconds();
            long expectedSummaries = seconds / SoakCadence.DOCUMENTED.summaryInterval().toSeconds();
            long expectedBatches = seconds / SoakCadence.DOCUMENTED.batchInterval().toSeconds();
            long expectedDisconnects = seconds / SoakCadence.DOCUMENTED.disconnectInterval().toSeconds();
            requireAtLeast(errors, cycles, "summaries", expectedSummaries);
            requireAtLeast(errors, cycles, "enhanceUnloadBatches", expectedBatches);
            requireAtLeast(errors, cycles, "disconnectRecoveries", expectedDisconnects);
            if (!cycles.path("continuousInvocations").isIntegralNumber()
                    || cycles.path("continuousInvocations").asLong() <= 0) {
                errors.add("passing evidence must contain at least one real continuous invocation");
            }
            if (cycles.path("continuousTargetEnhanceApplications").asInt() != 1) {
                errors.add("passing evidence must enhance the continuous target exactly once; lifecycle batches"
                        + " must use the class-isolated target");
            }
        }
    }

    private void validateTimeSeries(List<String> errors, JsonNode ts, JsonNode observations,
                                    Duration requestedDuration, boolean passing) {
        if (!ts.isObject()) {
            errors.add("missing timeSeries object (raw time-series path is required)");
            return;
        }
        String rawPath = textOrNull(ts, "rawPath");
        if (rawPath == null || rawPath.isBlank()) {
            errors.add("timeSeries.rawPath must be a non-blank in-repo/local path");
        }
        requireNonBlankText(errors, ts, "format");
        if (!ts.path("count").isInt() || ts.path("count").asInt() < (passing ? 1 : 0)) {
            errors.add("timeSeries.count must be >= " + (passing ? 1 : 0));
        }
        if (!ts.path("summaryIntervalSeconds").isInt()
                || ts.path("summaryIntervalSeconds").asInt()
                        != (int) SoakCadence.DOCUMENTED.summaryInterval().toSeconds()) {
            errors.add("timeSeries.summaryIntervalSeconds must equal the cadence summary interval ("
                    + SoakCadence.DOCUMENTED.summaryInterval().toSeconds() + ")");
        }
        // The in-result observations must be non-empty and reconcile to the time-series count.
        if (!observations.isArray() || (passing && observations.isEmpty())) {
            errors.add(passing
                    ? "observations must be a non-empty array (the per-minute time-series)"
                    : "observations must be an array");
            return;
        }
        if (ts.path("count").isInt() && observations.size() != ts.path("count").asInt()) {
            errors.add("observations.length (" + observations.size()
                    + ") must equal timeSeries.count (" + ts.path("count").asInt() + ")");
        }
        if (passing) {
            long expected = requestedDuration.toSeconds()
                    / SoakCadence.DOCUMENTED.summaryInterval().toSeconds();
            if (!ts.path("count").isInt() || ts.path("count").asInt() < expected) {
                errors.add("passing timeSeries.count must be >= " + expected
                        + " for requested duration " + requestedDuration);
            }
        }
        int prevMinute = 0;
        for (JsonNode o : observations) {
            validateObservation(errors, o);
            JsonNode mi = o.path("minuteIndex");
            if (mi.isInt()) {
                int expectedMinute = prevMinute + 1;
                if (mi.asInt() != expectedMinute) {
                    errors.add("observations minuteIndex must be contiguous from 1 (expected "
                            + expectedMinute + " but saw " + mi.asInt() + ")");
                }
                prevMinute = mi.asInt();
            }
        }
    }

    private void validateObservation(List<String> errors, JsonNode o) {
        if (!o.path("minuteIndex").isInt() || o.path("minuteIndex").asInt() < 1) {
            errors.add("observation minuteIndex must be >= 1");
        }
        requireNonBlankText(errors, o, "timestamp");
        if (!o.path("elapsedSeconds").isNumber() || o.path("elapsedSeconds").asLong() < 0) {
            errors.add("observation elapsedSeconds must be a non-negative number");
        }
        if (!o.path("heapUsedBytes").isIntegralNumber()) {
            errors.add("observation heapUsedBytes must be an integer");
        }
        if (!o.path("metaspaceUsedBytes").isIntegralNumber()) {
            errors.add("observation metaspaceUsedBytes must be an integer (-1 if unsupported)");
        }
        if (!o.path("threadCount").isInt()) {
            errors.add("observation threadCount must be int");
        }
        if (!o.path("openFdCount").isIntegralNumber()) {
            errors.add("observation openFdCount must be an integer (-1 if unsupported)");
        }
        if (!o.path("loadedClassCount").isInt()) {
            errors.add("observation loadedClassCount must be int");
        }
        for (String f : List.of("publishedRuleCount", "snapshotCount", "journalRecordCount",
                "instrumentationTypeCount", "instrumentationMethodCount", "batchesRun",
                "disconnectsRun")) {
            if (!o.path(f).isInt() || o.path(f).asInt() < 0) {
                errors.add("observation " + f + " must be a non-negative int");
            }
        }
        if (!o.path("continuousInvocations").isIntegralNumber() || o.path("continuousInvocations").asLong() < 0) {
            errors.add("observation continuousInvocations must be a non-negative integer");
        }
        for (String f : List.of("driftDetected", "heapBreach", "metaspaceBreach",
                "threadBreach", "fdBreach", "sustainedBreach")) {
            if (!o.path(f).isBoolean()) {
                errors.add("observation " + f + " must be boolean");
            }
        }
        if (!o.path("driftPersistentSeconds").isNumber() || o.path("driftPersistentSeconds").asLong() < 0) {
            errors.add("observation driftPersistentSeconds must be a non-negative number");
        }
    }

    private void validateDisconnectRecovery(List<String> errors, JsonNode dr, JsonNode cycles,
                                             Duration requestedDuration, boolean passing) {
        if (!dr.isObject()) {
            errors.add("missing disconnectRecovery object");
            return;
        }
        if (!dr.path("count").isInt() || dr.path("count").asInt() < 0) {
            errors.add("disconnectRecovery.count must be >= 0");
        }
        requireNonBlankText(errors, dr, "lastOutcome");
        if (dr.path("count").isInt() && cycles.path("disconnectRecoveries").isInt()
                && dr.path("count").asInt() != cycles.path("disconnectRecoveries").asInt()) {
            errors.add("disconnectRecovery.count must equal cycles.disconnectRecoveries");
        }
        JsonNode details = dr.path("details");
        if (!details.isArray()) {
            errors.add("disconnectRecovery.details must be an array");
        } else if (passing && dr.path("count").isInt()
                && details.size() != dr.path("count").asInt()) {
            errors.add("passing disconnectRecovery.details length must equal disconnectRecovery.count");
        } else if (!passing && dr.path("count").isInt()
                && details.size() < dr.path("count").asInt()) {
            errors.add("disconnectRecovery.details must cover every completed recovery");
        }
        if (passing) {
            long expected = requestedDuration.toSeconds()
                    / SoakCadence.DOCUMENTED.disconnectInterval().toSeconds();
            if (dr.path("count").isInt() && dr.path("count").asInt() < expected) {
                errors.add("passing disconnectRecovery.count must be >= " + expected
                        + " for requested duration " + requestedDuration);
            }
            if (dr.path("count").isInt()) {
                String expectedOutcome = dr.path("count").asInt() == 0 ? "NONE" : "RECOVERED";
                if (!expectedOutcome.equals(dr.path("lastOutcome").asText())) {
                    errors.add("passing disconnectRecovery.lastOutcome must be " + expectedOutcome);
                }
            }
        }
    }

    // -------------------------------------------------------- helpers

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        return n.isTextual() ? n.asText() : null;
    }

    private static void requireText(List<String> errors, JsonNode parent, String field, String expected) {
        JsonNode n = parent.path(field);
        if (!n.isTextual() || !expected.equals(n.asText())) {
            errors.add(field + " must equal '" + expected + "' (got: "
                    + (n.isTextual() ? n.asText() : "missing") + ")");
        }
    }

    private static void requireNonBlankText(List<String> errors, JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        if (!n.isTextual() || n.asText().isBlank()) {
            errors.add(field + " must be a non-blank string");
        }
    }

    private static void requireBoolean(List<String> errors, JsonNode parent, String field,
                                       boolean expected) {
        JsonNode n = parent.path(field);
        if (!n.isBoolean() || n.asBoolean() != expected) {
            errors.add("workloadTopology." + field + " must equal " + expected + " (got: "
                    + (n.isBoolean() ? n.asBoolean() : "missing") + ")");
        }
    }

    private static void checkInt(List<String> errors, JsonNode parent, String field, int expected) {
        JsonNode n = parent.path(field);
        if (!n.isInt() || n.asInt() != expected) {
            errors.add("budgets." + field + " must equal the documented value " + expected
                    + " (got: " + (n.isInt() ? n.asInt() : "missing") + ")");
        }
    }

    private static void requireAtLeast(List<String> errors, JsonNode parent, String field, long expected) {
        JsonNode n = parent.path(field);
        if (!n.isIntegralNumber() || n.asLong() < expected) {
            errors.add("cycles." + field + " must be >= " + expected
                    + " for the requested duration (got "
                    + (n.isIntegralNumber() ? n.asLong() : "missing") + ")");
        }
    }
}
