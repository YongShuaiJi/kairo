package com.example.kairo.perf.statecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic schema/content tests for {@link StateCycleResultValidator}. No JVM
 * lifecycle: builds a valid {@code state-cycle-result.json} fixture and asserts that
 * each documented failure mode is caught - missing scenario, count mismatch,
 * non-40-hex build id, skipped hash restoration, fake success, dirty PR evidence,
 * failed conflict, and a malformed result.
 */
class StateCycleResultValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SHA = "a".repeat(64);
    private static final String BUILD_ID = "0123456789abcdef0123456789abcdef01234567";

    private static ObjectNode validSample() {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("baselineHash", SHA);
        s.put("enhancedHash", "b".repeat(64));
        s.put("updatedHash", "c".repeat(64));
        s.put("afterUnloadHash", SHA); // == baseline
        s.put("enhancedBehavior", "77");
        s.put("updatedBehavior", "88");
        s.put("restoredBehavior", "10");
        s.put("enhancedDiffersFromBaseline", true);
        s.put("normalizedIdentical", true);
        s.put("hashRestored", true);
        s.put("rulesClearedAfterUnload", true);
        return s;
    }

    private static ObjectNode validScenario(String id, int cyclesRequested) {
        ObjectNode s = MAPPER.createObjectNode();
        s.put("id", id);
        s.put("category", "x");
        s.put("description", "x");
        s.put("concurrent", false);
        s.put("cyclesRequested", cyclesRequested);
        s.put("cyclesCompleted", cyclesRequested);
        s.put("cyclesFailed", 0);
        s.set("firstSample", validSample());
        s.set("lastSample", validSample());
        return s;
    }

    private static ObjectNode validResult(int requestedCycles) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("generatedAt", "2026-07-26T00:00:00Z");
        root.put("startedAt", "2026-07-26T00:00:00Z");
        root.put("endedAt", "2026-07-26T00:00:01Z");
        root.put("buildId", BUILD_ID);
        root.put("command", "./scripts/v1.7/run-state-cycle.sh --cycles 500 --output target/v1.7");
        root.put("mode", "pr");
        root.put("workingTreeDirty", false);
        root.putArray("jvmArgs").add("-Xms512m").add("-Xmx512m");
        ObjectNode env = root.putObject("environment");
        env.put("jdkVersion", "21");
        env.put("osName", "Linux");
        env.put("osArch", "amd64");
        env.put("availableProcessors", 4);
        env.put("javaHome", "/usr/lib/jvm/java-21");
        ObjectNode cycles = root.putObject("cycles");
        cycles.put("requested", requestedCycles);
        cycles.put("completed", requestedCycles);
        cycles.put("failed", 0);
        int[] distribution = StateCycleScenarioCatalog.distribute(requestedCycles);
        var scenarios = root.putArray("scenarios");
        List<String> ids = StateCycleScenarioCatalog.ids();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            ObjectNode sn = validScenario(id, distribution[i]);
            if (id.equals(StateCycleScenarioCatalog.concurrentScenario().id())) {
                sn.put("concurrent", true);
            }
            scenarios.add(sn);
        }
        ObjectNode conflict = root.putObject("concurrentConflict");
        conflict.put("threads", 8);
        conflict.put("applied", 1);
        conflict.put("staleRejected", 7);
        conflict.put("winnerRuleId", "conf-3");
        conflict.put("finalBehavior", "WIN-3");
        conflict.put("restoredBehavior", "value-1");
        conflict.put("mixedStateDetected", false);
        conflict.put("baselineHash", SHA);
        conflict.put("enhancedHash", "b".repeat(64));
        conflict.put("afterUnloadHash", SHA);
        conflict.put("hashRestored", true);
        conflict.put("normalizedIdentical", true);
        root.putNull("firstFailure");
        root.put("overall", "PASSED");
        return root;
    }

    private static List<String> validate(ObjectNode root, int requestedCycles) {
        return new StateCycleResultValidator().validate(root, requestedCycles);
    }

    private static ObjectNode firstSampleOf(ObjectNode root) {
        ArrayNode scenarios = (ArrayNode) root.get("scenarios");
        return (ObjectNode) ((ObjectNode) scenarios.get(0)).get("firstSample");
    }

    private static ObjectNode conflictOf(ObjectNode root) {
        return (ObjectNode) root.get("concurrentConflict");
    }

    private static ObjectNode findScenario(ObjectNode root, String id) {
        ArrayNode scenarios = (ArrayNode) root.get("scenarios");
        for (JsonNode s : scenarios) {
            if (id.equals(s.path("id").asText())) {
                return (ObjectNode) s;
            }
        }
        throw new AssertionError("scenario not found: " + id);
    }

    /**
     * A FAILED result: the first cyclic scenario (ordinary-method) failed at cycle 1
     * (the current product defect), so it is partial (0 completed, 1 failed) and every
     * later scenario - including concurrent - is un-run (0/0). No firstSample anywhere;
     * the concurrentConflict block is absent because concurrent never ran.
     */
    private static ObjectNode failedResult(int requestedCycles) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("generatedAt", "2026-07-26T00:00:00Z");
        root.put("startedAt", "2026-07-26T00:00:00Z");
        root.put("endedAt", "2026-07-26T00:00:01Z");
        root.put("buildId", BUILD_ID);
        root.put("command", "./scripts/v1.7/run-state-cycle.sh --cycles " + requestedCycles + " --output target/v1.7");
        root.put("mode", "pr");
        root.put("workingTreeDirty", false);
        root.putArray("jvmArgs").add("-Xms512m").add("-Xmx512m");
        ObjectNode env = root.putObject("environment");
        env.put("jdkVersion", "21");
        env.put("osName", "Linux");
        env.put("osArch", "amd64");
        env.put("availableProcessors", 4);
        env.put("javaHome", "/usr/lib/jvm/java-21");
        ObjectNode cycles = root.putObject("cycles");
        cycles.put("requested", requestedCycles);
        cycles.put("completed", 0);
        cycles.put("failed", 1);
        int[] distribution = StateCycleScenarioCatalog.distribute(requestedCycles);
        ArrayNode scenarios = root.putArray("scenarios");
        List<String> ids = StateCycleScenarioCatalog.ids();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            ObjectNode sn = MAPPER.createObjectNode();
            sn.put("id", id);
            sn.put("category", "x");
            sn.put("description", "x");
            sn.put("concurrent", id.equals(StateCycleScenarioCatalog.concurrentScenario().id()));
            sn.put("cyclesRequested", distribution[i]);
            // ordinary-method (first cyclic) failed at cycle 1; everything else un-run.
            sn.put("cyclesCompleted", 0);
            sn.put("cyclesFailed", i == 0 ? 1 : 0);
            // no firstSample: partial / un-run scenarios carry none.
            scenarios.add(sn);
        }
        root.putNull("concurrentConflict");
        ObjectNode ff = root.putObject("firstFailure");
        ff.put("scenario", "ordinary-method");
        ff.put("cycleIndex", 1);
        ff.put("phase", "hash-restore");
        ff.put("expected", "afterUnloadHash==baselineHash && normalizedIdentical");
        ff.put("actual", "baselineHash=X afterUnloadHash=Y normalizedIdentical=false");
        ff.put("detail", "bytecode hash not restored to baseline after full unload");
        root.put("overall", "FAILED");
        return root;
    }

    @Test
    void validResultPasses() {
        assertThat(validate(validResult(500), 500)).isEmpty();
    }

    @Test
    void missingScenarioFails() {
        ObjectNode r = validResult(500);
        ((ArrayNode) r.get("scenarios")).remove(0);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("missing scenario"));
    }

    @Test
    void extraScenarioFails() {
        ObjectNode r = validResult(500);
        ObjectNode extra = validScenario("bogus-scenario", 1);
        ((ArrayNode) r.get("scenarios")).add(extra);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("unknown scenario") || e.contains("exactly 6"));
    }

    @Test
    void countMismatchFails() {
        ObjectNode r = validResult(500);
        ((ObjectNode) r.get("cycles")).put("completed", 499);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("cycles.completed"));
    }

    @Test
    void requestedCyclesMismatchFails() {
        ObjectNode r = validResult(500);
        // requested=500 in fixture, but caller asks for 600
        assertThat(validate(r, 600)).anyMatch(e -> e.contains("cycles.requested"));
    }

    @Test
    void nonHexBuildIdFails() {
        ObjectNode r = validResult(500);
        r.put("buildId", "not-a-commit");
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("40-hex"));
    }

    @Test
    void nonHexHashFails() {
        ObjectNode r = validResult(500);
        firstSampleOf(r).put("baselineHash", "deadbeef");
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("64-hex SHA-256"));
    }

    @Test
    void skippedHashRestorationFails() {
        ObjectNode r = validResult(500);
        firstSampleOf(r).put("hashRestored", false);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("hashRestored"));
    }

    @Test
    void skippedNormalizedIdenticalFails() {
        ObjectNode r = validResult(500);
        firstSampleOf(r).put("normalizedIdentical", false);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("normalizedIdentical"));
    }

    @Test
    void enhancedDoesNotDifferFailsAsVacuous() {
        ObjectNode r = validResult(500);
        firstSampleOf(r).put("enhancedDiffersFromBaseline", false);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("non-vacuous"));
    }

    @Test
    void equalEnhancedAndBaselineHashesFail() {
        ObjectNode r = validResult(500);
        firstSampleOf(r).put("enhancedHash", SHA);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("enhancedHash must differ"));
    }

    @Test
    void malformedUpdatedHashFails() {
        ObjectNode r = validResult(500);
        firstSampleOf(r).put("updatedHash", "bad");
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("updatedHash"));
    }

    @Test
    void fakeSuccessFails() {
        ObjectNode r = validResult(500);
        ObjectNode ff = r.putObject("firstFailure");
        ff.put("scenario", "ordinary-method");
        ff.put("cycleIndex", 1);
        ff.put("phase", "enhanced-behavior");
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("firstFailure is present"));
    }

    @Test
    void dirtyPrEvidenceFails() {
        ObjectNode r = validResult(500);
        r.put("workingTreeDirty", true);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("dirty"));
    }

    @Test
    void failedConflictFails() {
        ObjectNode r = validResult(500);
        conflictOf(r).put("mixedStateDetected", true);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("mixedStateDetected"));
    }

    @Test
    void conflictAppliedCountNotOneFails() {
        ObjectNode r = validResult(500);
        conflictOf(r).put("applied", 2);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("applied must be exactly 1"));
    }

    @Test
    void conflictHashNotRestoredFails() {
        ObjectNode r = validResult(500);
        conflictOf(r).put("afterUnloadHash", "c".repeat(64));
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("afterUnloadHash must equal baselineHash"));
    }

    @Test
    void conflictEnhancedHashMustDiffer() {
        ObjectNode r = validResult(500);
        conflictOf(r).put("enhancedHash", SHA);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("enhancedHash must differ"));
    }

    @Test
    void missingConcurrentConflictFails() {
        ObjectNode r = validResult(500);
        r.remove("concurrentConflict");
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("concurrentConflict"));
    }

    @Test
    void rulesLeakedFails() {
        ObjectNode r = validResult(500);
        firstSampleOf(r).put("rulesClearedAfterUnload", false);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("rulesClearedAfterUnload"));
    }

    @Test
    void nullResultFails() {
        JsonNode n = MAPPER.nullNode();
        assertThat(new StateCycleResultValidator().validate(n, 500)).isNotEmpty();
    }

    // ---- defect 4: partial counts allowed on FAILED, exact on PASSED ----

    @Test
    void failedResultWithPartialFailingScenarioIsValid() {
        // A FAILED result's failing scenario is partial (0 completed, 1 failed of N
        // requested); arithmetic must NOT be enforced on FAILED (defect 4).
        ObjectNode r = failedResult(500);
        List<String> errors = validate(r, 500);
        assertThat(errors).as("partial counts must be allowed on FAILED (defect 4)").isEmpty();
    }

    @Test
    void failedResultRejectsScenarioCountOverrun() {
        ObjectNode r = failedResult(500);
        ObjectNode ordinary = findScenario(r, "ordinary-method");
        ordinary.put("cyclesCompleted", ordinary.get("cyclesRequested").asInt());
        ordinary.put("cyclesFailed", 1);
        ((ObjectNode) r.get("cycles")).put("completed", ordinary.get("cyclesRequested").asInt());
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("must not exceed cyclesRequested"));
    }

    @Test
    void failedResultRejectsAggregateCountOverrun() {
        ObjectNode r = failedResult(500);
        ((ObjectNode) r.get("cycles")).put("completed", 500);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("must not exceed cycles.requested"));
    }

    @Test
    void passedResultRejectsPartialArithmeticPerScenario() {
        // PASSED stays exact: a partial cyclic scenario must fail arithmetic even when
        // the aggregates would otherwise be consistent (defect 4).
        ObjectNode r = validResult(500);
        ObjectNode ord = findScenario(r, "ordinary-method");
        ord.put("cyclesCompleted", ord.get("cyclesRequested").asInt() - 1);
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("cyclesCompleted + cyclesFailed"));
    }

    // ---- defect 2: concurrent firstSample exemption ----

    @Test
    void concurrentScenarioMayOmitFirstSample() {
        // The concurrent scenario's evidence is concurrentConflict, not a per-cycle
        // firstSample, so a completed concurrent row without firstSample is valid.
        ObjectNode r = validResult(500);
        ObjectNode concurrent = findScenario(r, StateCycleScenarioCatalog.concurrentScenario().id());
        concurrent.remove("firstSample");
        concurrent.remove("lastSample");
        assertThat(validate(r, 500)).as("concurrent firstSample not required (defect 2)").isEmpty();
    }

    @Test
    void nonConcurrentScenarioStillRequiresFirstSample() {
        // The exemption is concurrent-only: a cyclic scenario with completed>=1 and no
        // firstSample must still fail (defect 2).
        ObjectNode r = validResult(500);
        findScenario(r, "ordinary-method").remove("firstSample");
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("missing firstSample"));
    }

    // ---- defect 3: conflict counts must reconcile to threads ----

    @Test
    void conflictCountsMustReconcileToThreads() {
        // applied + staleRejected must equal threads: an unrecognised status must not
        // leak into the recorded evidence (defect 3).
        ObjectNode r = validResult(500);
        conflictOf(r).put("staleRejected", 6); // 1 + 6 = 7 != 8
        assertThat(validate(r, 500)).anyMatch(e -> e.contains("must equal threads"));
    }
}
