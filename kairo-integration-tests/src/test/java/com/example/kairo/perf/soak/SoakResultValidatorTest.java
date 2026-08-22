package com.example.kairo.perf.soak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic schema/content tests for {@link SoakResultValidator}. A valid PASSED result
 * must validate clean; each mutation must surface a precise error, so the evidence cannot
 * drift from what the harness actually measures and a "fake success" cannot pass.
 */
class SoakResultValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration REQUESTED = Duration.ofMinutes(2);

    private static final String VALID = """
            {
              "schemaVersion": "1.0",
              "generatedAt": "2026-07-31T00:31:00Z",
              "startedAt": "2026-07-31T00:00:00Z",
              "endedAt": "2026-07-31T00:31:00Z",
              "buildId": "0123456789abcdef0123456789abcdef01234567",
              "command": "./scripts/v1.7/run-soak.sh --duration PT2M --output target/v1.7",
              "mode": "pr",
              "workingTreeDirty": false,
              "jvmArgs": ["-Xms512m", "-Xmx512m"],
              "environment": { "jdkVersion": "21.0.11", "osName": "Mac OS X", "osArch": "aarch64", "availableProcessors": 10 },
              "duration": { "requested": "PT2M", "requestedSeconds": 120, "completedSeconds": 120.0, "completedIso": "PT2M", "completed": true },
              "cadence": { "summaryInterval": "PT1M", "batchInterval": "PT5M", "disconnectInterval": "PT30M" },
              "workloadTopology": { "continuousTargetClass": "com.example.demo.OrderService", "lifecycleTargetClass": "com.example.demo.SoakLifecycleTarget", "classSeparated": true, "lifecycleClassLoaderPerBatch": true, "continuousTargetParticipatesInLifecycleBatches": false, "lifecycleTargetReceivesContinuousTraffic": false },
              "measurementWarmup": { "strategy": "bounded-adaptive-metaspace-plateau", "enhanceUnloadBatch": true, "disconnectRecovery": true, "resourceSample": true, "excludedFromDurationAndCycles": true, "minimumLifecycleBatches": 128, "maximumLifecycleBatches": 512, "sampleEveryBatches": 32, "plateauWindowBatches": 128, "maxWindowMetaspaceGrowthPct": 2.0, "batchesRun": 256, "steadyStateEstablished": true, "initialMetaspaceUsedBytes": 38000000, "finalMetaspaceUsedBytes": 41000000, "observedWindowMetaspaceGrowthPct": 1.2, "lifecycleLoadersCreated": 256, "lifecycleLoadersCollected": 224, "lifecycleLoadersOutstanding": 32, "eligibleLifecycleLoaders": 224, "eligibleLifecycleLoadersOutstanding": 0, "latestCohortGraceLoaders": 32, "allowedOutstandingLifecycleLoaders": 2, "samples": [{"lifecycleBatches":128,"metaspaceUsedBytes":40500000},{"lifecycleBatches":256,"metaspaceUsedBytes":41000000}] },
              "budgets": { "maxHeapGrowthPct": 15, "maxMetaspaceGrowthPct": 10, "maxThreadDelta": 2, "maxFdDelta": 5, "driftThresholdSeconds": 300, "sustainedBreachWindowSeconds": 300 },
              "cycles": { "continuousInvocations": 1000000, "continuousTargetEnhanceApplications": 1, "enhanceUnloadBatches": 0, "disconnectRecoveries": 0, "summaries": 2, "failedBatches": 0 },
              "continuousRuleHealth": { "automaticCircuitOpenEvents": 0, "automaticCircuitRecoveries": 0, "circuitOpenAtEnd": false, "lastCircuitBreakReason": null, "transitions": [] },
              "timeSeries": { "rawPath": "target/v1.7/soak-timeseries.jsonl", "format": "jsonl", "count": 2, "summaryIntervalSeconds": 60 },
              "observations": [
                { "minuteIndex": 1, "timestamp": "2026-07-31T00:01:00Z", "elapsedSeconds": 60, "heapUsedBytes": 100, "metaspaceUsedBytes": 10, "threadCount": 10, "openFdCount": 50, "loadedClassCount": 1000, "publishedRuleCount": 1, "snapshotCount": 0, "journalRecordCount": 0, "instrumentationTypeCount": 0, "instrumentationMethodCount": 0, "continuousInvocations": 1000, "batchesRun": 0, "disconnectsRun": 0, "driftDetected": false, "driftPersistentSeconds": 0, "heapBreach": false, "metaspaceBreach": false, "threadBreach": false, "fdBreach": false, "sustainedBreach": false },
                { "minuteIndex": 2, "timestamp": "2026-07-31T00:02:00Z", "elapsedSeconds": 120, "heapUsedBytes": 100, "metaspaceUsedBytes": 10, "threadCount": 10, "openFdCount": 50, "loadedClassCount": 1000, "publishedRuleCount": 1, "snapshotCount": 0, "journalRecordCount": 0, "instrumentationTypeCount": 0, "instrumentationMethodCount": 0, "continuousInvocations": 2000, "batchesRun": 0, "disconnectsRun": 0, "driftDetected": false, "driftPersistentSeconds": 0, "heapBreach": false, "metaspaceBreach": false, "threadBreach": false, "fdBreach": false, "sustainedBreach": false }
              ],
              "disconnectRecovery": { "count": 0, "lastOutcome": "NONE", "details": [] },
              "oomEvidence": false,
              "firstFailure": null,
              "finalState": "COMPLETED",
              "overall": "PASSED"
            }
            """;

    private ObjectNode valid() throws Exception {
        return (ObjectNode) MAPPER.readTree(VALID);
    }

    private List<String> validate(ObjectNode root) {
        return new SoakResultValidator().validate(root, REQUESTED);
    }

    @Test
    void validPassedResultHasNoErrors() throws Exception {
        assertThat(validate(valid())).isEmpty();
    }

    @Test
    void validFailedResultWithFirstFailureHasNoErrors() throws Exception {
        ObjectNode r = valid();
        r.put("overall", "FAILED");
        r.put("finalState", "FAILED");
        ((ObjectNode) r.path("duration")).put("completed", false);
        ObjectNode ff = r.putObject("firstFailure");
        ff.put("scenario", "batch-3");
        ff.put("phase", "precise-unload");
        ff.put("reason", "lifecycle-failure");
        ff.put("expected", "afterUnloadHash==baselineHash");
        ff.put("actual", "mismatch");
        ff.put("detail", "hash not restored");
        ff.put("failureTime", "2026-07-31T00:15:00Z");
        ff.put("failureSeconds", 900);
        assertThat(validate(r)).isEmpty();
    }

    @Test
    void oomFailureIsAccepted() throws Exception {
        ObjectNode r = valid();
        r.put("overall", "FAILED");
        r.put("finalState", "ABORTED");
        r.put("oomEvidence", true);
        ((ObjectNode) r.path("duration")).put("completed", false);
        ObjectNode ff = r.putObject("firstFailure");
        ff.put("scenario", "oom");
        ff.put("phase", "execute");
        ff.put("reason", "out-of-memory");
        ff.put("expected", "");
        ff.put("actual", "OutOfMemoryError");
        ff.put("detail", "java heap space");
        ff.put("failureTime", "2026-07-31T00:45:00Z");
        ff.put("failureSeconds", 2700);
        assertThat(validate(r)).isEmpty();
    }

    @Test
    void badCadenceFails() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("cadence")).put("summaryInterval", "PT2M");
        assertThat(validate(r)).anyMatch(e -> e.contains("summaryInterval"));
    }

    @Test
    void badBudgetFails() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("budgets")).put("maxHeapGrowthPct", 50);
        assertThat(validate(r)).anyMatch(e -> e.contains("maxHeapGrowthPct"));
    }

    @Test
    void sameHotAndLifecycleClassFailsClosed() throws Exception {
        ObjectNode r = valid();
        ObjectNode topology = (ObjectNode) r.path("workloadTopology");
        topology.put("lifecycleTargetClass", topology.path("continuousTargetClass").asText());
        topology.put("classSeparated", false);
        assertThat(validate(r)).anyMatch(e -> e.contains("different classes"));
        assertThat(validate(r)).anyMatch(e -> e.contains("classSeparated"));
    }

    @Test
    void retransformedHotTargetEvidenceFailsClosed() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("cycles")).put("continuousTargetEnhanceApplications", 2);
        assertThat(validate(r)).anyMatch(e -> e.contains("enhance the continuous target exactly once"));
    }

    @Test
    void unprovenWarmupSteadyStateFailsClosed() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("measurementWarmup")).put("steadyStateEstablished", false);
        assertThat(validate(r)).anyMatch(e -> e.contains("steadyStateEstablished"));
    }

    @Test
    void unreclaimedLifecycleLoadersFailClosed() throws Exception {
        ObjectNode r = valid();
        ObjectNode warmup = (ObjectNode) r.path("measurementWarmup");
        warmup.put("eligibleLifecycleLoadersOutstanding", 3);
        assertThat(validate(r)).anyMatch(e -> e.contains("unreclaimed lifecycle ClassLoaders"));
    }

    @Test
    void inconsistentLifecycleLoaderTotalsFailClosed() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("measurementWarmup")).put("lifecycleLoadersCollected", 200);
        assertThat(validate(r)).anyMatch(e -> e.contains("totals do not reconcile"));
    }

    @Test
    void fakeSuccessWithFirstFailureFails() throws Exception {
        ObjectNode r = valid();
        ObjectNode ff = r.putObject("firstFailure");
        ff.put("phase", "precise-unload");
        ff.put("reason", "lifecycle-failure");
        ff.put("failureTime", "2026-07-31T00:15:00Z");
        assertThat(validate(r)).anyMatch(e -> e.contains("fake success"));
    }

    @Test
    void failedWithoutFirstFailureFails() throws Exception {
        ObjectNode r = valid();
        r.put("overall", "FAILED");
        r.put("finalState", "FAILED");
        assertThat(validate(r)).anyMatch(e -> e.contains("overall is FAILED but firstFailure is absent"));
    }

    @Test
    void firstFailureMissingFieldsFails() throws Exception {
        ObjectNode r = valid();
        r.put("overall", "FAILED");
        r.put("finalState", "FAILED");
        ObjectNode ff = r.putObject("firstFailure");
        ff.put("phase", "precise-unload"); // missing reason + failureTime
        List<String> errors = validate(r);
        assertThat(errors).anyMatch(e -> e.contains("reason") || e.contains("failureTime"));
    }

    @Test
    void missingTimeSeriesRawPathFails() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("timeSeries")).put("rawPath", "");
        assertThat(validate(r)).anyMatch(e -> e.contains("rawPath"));
    }

    @Test
    void observationsCountMismatchFails() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("timeSeries")).put("count", 99);
        assertThat(validate(r)).anyMatch(e -> e.contains("observations.length"));
    }

    @Test
    void placeholderCommandFails() throws Exception {
        ObjectNode r = valid();
        r.put("command", "./scripts/v1.7/run-soak.sh <duration> <output>");
        assertThat(validate(r)).anyMatch(e -> e.contains("placeholder"));
    }

    @Test
    void dirtyPrEvidenceFails() throws Exception {
        ObjectNode r = valid();
        r.put("workingTreeDirty", true);
        assertThat(validate(r)).anyMatch(e -> e.contains("dirty working tree"));
    }

    @Test
    void durationRequestMismatchFails() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("duration")).put("requested", "PT1H");
        assertThat(validate(r)).anyMatch(e -> e.contains("duration.requested"));
    }

    @Test
    void completedFlagCannotHideTooShortExecution() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("duration")).put("completedSeconds", 1.0);
        assertThat(validate(r)).anyMatch(e -> e.contains("shorter than requested"));
    }

    @Test
    void longDurationCannotPassWithTooFewCadenceEvents() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("duration")).put("requested", "PT2H");
        ((ObjectNode) r.path("duration")).put("requestedSeconds", 7200);
        ((ObjectNode) r.path("duration")).put("completedSeconds", 7200.0);
        List<String> errors = new SoakResultValidator().validate(r, Duration.ofHours(2));
        assertThat(errors).anyMatch(e -> e.contains("summaries must be >= 120"));
        assertThat(errors).anyMatch(e -> e.contains("enhanceUnloadBatches must be >= 24"));
        assertThat(errors).anyMatch(e -> e.contains("disconnectRecoveries must be >= 4"));
    }

    @Test
    void passedButNotCompletedFails() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("duration")).put("completed", false);
        assertThat(validate(r)).anyMatch(e -> e.contains("duration.completed"));
    }

    @Test
    void passedWithFailedDisconnectFails() throws Exception {
        ObjectNode r = valid();
        ((ObjectNode) r.path("disconnectRecovery")).put("lastOutcome", "FAILED");
        assertThat(validate(r)).anyMatch(e -> e.contains("lastOutcome"));
    }

    @Test
    void zeroDisconnectShortRunMayPassWithNoneOutcome() throws Exception {
        // A run shorter than the 30m disconnect cadence legitimately has 0 disconnects / NONE.
        ObjectNode r = valid();
        ((ObjectNode) r.path("disconnectRecovery")).put("count", 0);
        ((ObjectNode) r.path("disconnectRecovery")).put("lastOutcome", "NONE");
        assertThat(validate(r)).isEmpty();
    }

    @Test
    void unbalancedCircuitEvidenceFailsClosed() throws Exception {
        ObjectNode r = valid();
        ObjectNode health = (ObjectNode) r.path("continuousRuleHealth");
        health.put("automaticCircuitOpenEvents", 1);
        health.put("automaticCircuitRecoveries", 0);
        health.put("circuitOpenAtEnd", false);
        health.put("lastCircuitBreakReason", "TIMEOUT");

        assertThat(validate(r)).anyMatch(error -> error.contains("do not reconcile"));
    }

    @Test
    void recoveredCircuitRequiresTimestampedTransitions() throws Exception {
        ObjectNode r = valid();
        ObjectNode health = (ObjectNode) r.path("continuousRuleHealth");
        health.put("automaticCircuitOpenEvents", 1);
        health.put("automaticCircuitRecoveries", 1);
        health.put("lastCircuitBreakReason", "TIMEOUT");

        assertThat(validate(r)).anyMatch(error -> error.contains("transitions do not match"));
    }
}
