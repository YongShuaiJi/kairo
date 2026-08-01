package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic schema/content tests for {@link CompatibilityRowValidator}. Builds
 * valid and mutated row documents directly (no JVM, no scenario) and asserts the
 * validator accepts well-formed PASSED / NOT_RUN / EXPERIMENTAL / FAILED rows and
 * rejects the specific malformations that would let a fabricated PASSED, a wrong build
 * id, wrong platform/load metadata, wrong mode/dirty state, a missing/same-as-runner
 * child PID, or a missing field slip through.
 */
class CompatibilityRowValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CompatibilityRowFixtures FX = new CompatibilityRowFixtures(MAPPER);

    private List<String> validate(ObjectNode row) {
        return new CompatibilityRowValidator().validate(row);
    }

    // --- legal structural input ---

    @Test
    void validPassedRowHasNoErrors() {
        assertThat(validate(FX.passedRow("C01"))).isEmpty();
        assertThat(validate(FX.passedRow("C07"))).isEmpty(); // multi-JDK scenario
        assertThat(validate(FX.passedRow("C10"))).isEmpty();
    }

    @Test
    void validNotRunRowHasNoErrors() {
        assertThat(validate(FX.notRunRow("C01"))).isEmpty();
        assertThat(validate(FX.notRunRow("C10"))).isEmpty();
    }

    @Test
    void validExperimentalC09RowHasNoErrors() {
        assertThat(validate(FX.experimentalC09Row())).isEmpty();
    }

    @Test
    void validFailedRowWithFailingAssertionHasNoErrors() {
        ObjectNode row = FX.passedRow("C01");
        row.put("status", "FAILED");
        row.put("failureReason", "assertion failed: 卸载");
        ((ObjectNode) ((ArrayNode) row.path("assertions")).get(3)).put("passed", false);
        assertThat(validate(row)).isEmpty();
    }

    // --- fake PASSED evidence rejected ---

    @Test
    void fakePassedMissingPidRejected() {
        ObjectNode row = FX.passedRow("C01");
        ((ObjectNode) row.path("targetJvm")).put("pid", 0);
        assertThat(validate(row)).anyMatch(e -> e.contains("real independent child PID > 0"));
    }

    @Test
    void fakePassedNotIndependentRejected() {
        ObjectNode row = FX.passedRow("C01");
        ((ObjectNode) row.path("targetJvm")).put("independent", false);
        assertThat(validate(row)).anyMatch(e -> e.contains("independent=true"));
    }

    @Test
    void fakePassedChildPidEqualsRunnerPidRejected() {
        ObjectNode row = FX.passedRow("C01");
        // Target PID == runner PID: not an independent process.
        ((ObjectNode) row.path("targetJvm")).put("pid", CompatibilityRowFixtures.RUNNER_PID);
        assertThat(validate(row)).anyMatch(e -> e.contains("!= environment.runnerPid"));
    }

    @Test
    void fakePassedAssertionFailedRejected() {
        ObjectNode row = FX.passedRow("C01");
        ((ObjectNode) ((ArrayNode) row.path("assertions")).get(1)).put("passed", false);
        assertThat(validate(row)).anyMatch(e -> e.contains("every assertion.passed=true"));
    }

    @Test
    void fakePassedMissingBehaviorCoverageRejected() {
        ObjectNode row = FX.passedRow("C01");
        ((ArrayNode) row.path("assertions")).remove(0); // drop one behavior's assertion
        assertThat(validate(row)).anyMatch(e -> e.contains("covering required behavior"));
    }

    @Test
    void fakePassedWrongTargetJdkRejected() {
        ObjectNode row = FX.passedRow("C01"); // catalog JDK 17
        ((ObjectNode) row.path("targetJvm")).put("jdkVersion", "21.0.11");
        assertThat(validate(row)).anyMatch(e -> e.contains("not in the catalog target JDKs"));
    }

    @Test
    void fakePassedWrongPlatformRejected() {
        ObjectNode row = FX.passedRow("C01"); // catalog Linux x86_64
        ((ObjectNode) row.path("environment")).put("osArch", "aarch64");
        assertThat(validate(row)).anyMatch(e -> e.contains("must match the catalog runner arch"));
    }

    // --- wrong build id / platform / load metadata ---

    @Test
    void wrongBuildIdRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.put("buildId", CompatibilityRowFixtures.OTHER_BUILD.replace("f", "x")); // not 40-hex
        assertThat(validate(row)).anyMatch(e -> e.contains("buildId must be a 40-hex"));
    }

    @Test
    void wrongLoadModeRejected() {
        ObjectNode row = FX.passedRow("C02"); // external attach/agentmain
        row.put("loadingMode", "premain");
        assertThat(validate(row)).anyMatch(e -> e.contains("loadingMode must equal"));
    }

    @Test
    void wrongFixtureRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.put("fixture", "Spring Boot 3 executable jar");
        assertThat(validate(row)).anyMatch(e -> e.contains("fixture must equal"));
    }

    @Test
    void catalogBlockMismatchRejected() {
        ObjectNode row = FX.passedRow("C01");
        ((ObjectNode) row.path("catalog")).put("runnerArch", "arm64");
        assertThat(validate(row)).anyMatch(e -> e.contains("runnerArch must equal"));
    }

    @Test
    void wrongSupportLevelRejected() {
        ObjectNode row = FX.passedRow("C09");
        row.put("supportLevel", "FORMAL"); // C09 is EXPERIMENTAL
        assertThat(validate(row)).anyMatch(e -> e.contains("supportLevel must be"));
    }

    // --- mode / dirty provenance (correction 3) ---

    @Test
    void missingModeRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.remove("mode");
        assertThat(validate(row)).anyMatch(e -> e.contains("mode must be 'pr' or 'dev'"));
    }

    @Test
    void invalidModeRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.put("mode", "rc");
        assertThat(validate(row)).anyMatch(e -> e.contains("mode must be 'pr' or 'dev'"));
    }

    @Test
    void missingWorkingTreeDirtyRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.remove("workingTreeDirty");
        assertThat(validate(row)).anyMatch(e -> e.contains("workingTreeDirty must be boolean"));
    }

    @Test
    void prModeWithDirtyTreeRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.put("mode", "pr");
        row.put("workingTreeDirty", true);
        assertThat(validate(row)).anyMatch(e -> e.contains("must not have a dirty working tree"));
    }

    @Test
    void prModeCleanAccepted() {
        ObjectNode row = FX.passedRow("C01");
        row.put("mode", "pr");
        assertThat(validate(row)).isEmpty();
    }

    // --- runnerPid provenance (correction 4) ---

    @Test
    void missingRunnerPidRejected() {
        ObjectNode row = FX.passedRow("C01");
        ((ObjectNode) row.path("environment")).remove("runnerPid");
        assertThat(validate(row)).anyMatch(e -> e.contains("environment.runnerPid must be a positive integer"));
    }

    @Test
    void zeroRunnerPidRejected() {
        ObjectNode row = FX.passedRow("C01");
        ((ObjectNode) row.path("environment")).put("runnerPid", 0);
        assertThat(validate(row)).anyMatch(e -> e.contains("environment.runnerPid must be a positive integer"));
    }

    @Test
    void failedChildPidEqualsRunnerPidRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.put("status", "FAILED");
        row.put("failureReason", "assertion failed");
        ((ObjectNode) ((ArrayNode) row.path("assertions")).get(0)).put("passed", false);
        ((ObjectNode) row.path("targetJvm")).put("pid", CompatibilityRowFixtures.RUNNER_PID);
        assertThat(validate(row)).anyMatch(e -> e.contains("!= environment.runnerPid"));
    }

    @Test
    void notRunTargetPidZeroAccepted() {
        // NOT_RUN may keep target PID 0 even though runnerPid > 0.
        ObjectNode row = FX.notRunRow("C01");
        assertThat(validate(row)).isEmpty();
    }

    // --- malformed / missing fields ---

    @Test
    void nullRowRejected() {
        assertThat(validate(null)).anyMatch(e -> e.contains("null/missing"));
    }

    @Test
    void missingScenarioRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.remove("scenario");
        assertThat(validate(row)).anyMatch(e -> e.contains("scenario is required"));
    }

    @Test
    void unknownScenarioRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.put("scenario", "C11");
        assertThat(validate(row)).anyMatch(e -> e.contains("unknown scenario"));
    }

    @Test
    void missingFailureReasonForNonPassedRejected() {
        ObjectNode row = FX.notRunRow("C01");
        row.put("failureReason", "");
        assertThat(validate(row)).anyMatch(e -> e.contains("failureReason is missing/blank"));
    }

    @Test
    void passedWithFailureReasonRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.put("failureReason", "should not be here");
        assertThat(validate(row)).anyMatch(e -> e.contains("PASSED but failureReason is present"));
    }

    @Test
    void missingTargetJvmRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.remove("targetJvm");
        assertThat(validate(row)).anyMatch(e -> e.contains("missing targetJvm"));
    }

    @Test
    void endedBeforeStartedRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.put("startedAt", "2026-08-01T00:02:00Z");
        row.put("endedAt", "2026-08-01T00:01:00Z");
        assertThat(validate(row)).anyMatch(e -> e.contains("endedAt must not be before startedAt"));
    }

    @Test
    void placeholderCommandRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.put("command", "<...>");
        assertThat(validate(row)).anyMatch(e -> e.contains("placeholders"));
    }

    @Test
    void statusesAreTheFixedFive() {
        assertThat(CompatibilityRowValidator.STATUSES)
                .containsExactlyInAnyOrder("PASSED", "FAILED", "SKIPPED", "NOT_RUN", "EXPERIMENTAL");
    }

    @Test
    void assertionsOfWrongTypeRejected() {
        ObjectNode row = FX.passedRow("C01");
        row.put("assertions", "not-an-array");
        assertThat(validate(row)).anyMatch(e -> e.contains("assertions must be an array"));
    }
}
