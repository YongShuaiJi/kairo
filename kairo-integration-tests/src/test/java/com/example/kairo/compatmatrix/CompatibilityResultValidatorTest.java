package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic verifier tests (section 10.4.1). The verifier re-runs
 * {@link CompatibilityRowValidator} on every aggregate row (so it independently rejects
 * tampering of any evidence category), validates catalog completeness, a single
 * candidate build id, formal-row status semantics, C09 completion (PASSED or
 * EXPERIMENTAL per section 10.5), summary/count consistency, exact non-formal
 * exclusions and an ISO-8601 generatedAt. It does not rerun scenarios.
 */
class CompatibilityResultValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CompatibilityRowFixtures FX = new CompatibilityRowFixtures(MAPPER);
    private static final CompatibilityRowAggregator.AggregatorMeta META =
            new CompatibilityRowAggregator.AggregatorMeta("2026-08-01T00:00:00Z",
                    "./scripts/v1.7/aggregate-compatibility.sh --input x --output y");

    private List<String> validate(ObjectNode result) {
        return new CompatibilityResultValidator().validate(result);
    }

    private ObjectNode aggregate(List<JsonNode> rows) {
        return new CompatibilityRowAggregator(MAPPER).aggregate(wrap(rows), META).result();
    }

    private ObjectNode passingResult() {
        return aggregate(FX.passingMatrix());
    }

    private ObjectNode notRunResult() {
        return aggregate(FX.m3aNotRunMatrix());
    }

    private static List<CompatibilityRowAggregator.ParsedRow> wrap(List<JsonNode> rows) {
        List<CompatibilityRowAggregator.ParsedRow> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(new CompatibilityRowAggregator.ParsedRow("row-" + i + ".json", rows.get(i), null));
        }
        return out;
    }

    private ObjectNode row(ObjectNode result, String scenarioId) {
        for (JsonNode r : result.path("rows")) {
            if (r.get("scenario").asText().equals(scenarioId)) {
                return (ObjectNode) r;
            }
        }
        throw new AssertionError("row not found: " + scenarioId);
    }

    /** Replace C09 in a passing matrix with a valid C09 row of the given status. */
    private List<JsonNode> matrixWithC09Status(String status) {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        rows.removeIf(r -> "C09".equals(r.get("scenario").asText()));
        ObjectNode c09 = FX.passedRow("C09");
        switch (status) {
            case "PASSED" -> { /* already PASSED */ }
            case "EXPERIMENTAL" -> {
                c09.put("status", "EXPERIMENTAL");
                c09.put("failureReason", "experimental");
                ((ObjectNode) c09.path("targetJvm")).put("pid", 0).put("independent", false).put("jdkVersion", "");
                c09.putArray("assertions");
            }
            case "FAILED" -> {
                c09.put("status", "FAILED");
                c09.put("failureReason", "attach failed");
                ((ObjectNode) ((ArrayNode) c09.path("assertions")).get(0)).put("passed", false);
            }
            case "SKIPPED", "NOT_RUN" -> {
                c09.put("status", status);
                c09.put("failureReason", status.toLowerCase());
                ((ObjectNode) c09.path("targetJvm")).put("pid", 0).put("independent", false).put("jdkVersion", "");
                c09.putArray("assertions");
            }
            default -> throw new IllegalArgumentException(status);
        }
        rows.add(c09);
        return rows;
    }

    // --- legal ---

    @Test
    void validPassingResultHasNoErrors() {
        assertThat(validate(passingResult())).isEmpty();
    }

    @Test
    void validNotRunResultHasNoErrors() {
        // A FAILED aggregate is still structurally valid (verifier-acceptable); it just
        // reports overall=FAILED with failures.
        assertThat(validate(notRunResult())).isEmpty();
    }

    // --- catalog completeness ---

    @Test
    void missingRowFails() {
        ObjectNode r = passingResult();
        ((ArrayNode) r.path("rows")).remove(4); // drop C05
        assertThat(validate(r)).anyMatch(e -> e.contains("missing row for scenario C05"));
    }

    @Test
    void duplicateRowFails() {
        ObjectNode r = passingResult();
        ((ArrayNode) r.path("rows")).add(row(r, "C01").deepCopy());
        assertThat(validate(r)).anyMatch(e -> e.contains("duplicate row for scenario C01"));
    }

    @Test
    void unknownScenarioInRowsFails() {
        ObjectNode r = passingResult();
        ObjectNode extra = row(r, "C01").deepCopy();
        extra.put("scenario", "C42");
        ((ArrayNode) r.path("rows")).add(extra);
        assertThat(validate(r)).anyMatch(e -> e.contains("must be a known C01-C10"));
    }

    // --- build id ---

    @Test
    void buildIdNotHexFails() {
        ObjectNode r = passingResult();
        r.put("buildId", "not-a-commit");
        assertThat(validate(r)).anyMatch(e -> e.contains("single 40-hex candidate"));
    }

    @Test
    void buildIdMismatchWithRowsFails() {
        ObjectNode r = passingResult();
        row(r, "C01").put("buildId", CompatibilityRowFixtures.OTHER_BUILD);
        assertThat(validate(r)).anyMatch(e -> e.contains("does not match row buildId"));
    }

    // --- formal-row status semantics ---

    @Test
    void formalRowNotPassedMakesOverallInconsistent() {
        // A PASSED aggregate with a formal row not PASSED is a fake aggregate success.
        ObjectNode r = passingResult();
        ObjectNode c03 = row(r, "C03");
        c03.put("status", "NOT_RUN");
        c03.put("failureReason", "not run");
        ((ObjectNode) c03.path("targetJvm")).put("pid", 0).put("independent", false).put("jdkVersion", "");
        c03.putArray("assertions");
        List<String> errors = validate(r);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.contains("formalComplete")
                || e.contains("formal scenarios are PASSED"));
    }

    // --- C09 completion (correction 1 / section 10.5) ---

    @Test
    void c09FailedIsRejected() {
        ObjectNode r = aggregate(matrixWithC09Status("FAILED"));
        assertThat(validate(r)).anyMatch(e -> e.contains("C09 must be PASSED or EXPERIMENTAL"));
    }

    @Test
    void c09SkippedIsRejected() {
        ObjectNode r = aggregate(matrixWithC09Status("SKIPPED"));
        assertThat(validate(r)).anyMatch(e -> e.contains("C09 must be PASSED or EXPERIMENTAL"));
    }

    @Test
    void c09NotRunIsRejected() {
        ObjectNode r = aggregate(matrixWithC09Status("NOT_RUN"));
        assertThat(validate(r)).anyMatch(e -> e.contains("C09 must be PASSED or EXPERIMENTAL"));
    }

    @Test
    void c09PassedAndExperimentalAreAccepted() {
        assertThat(validate(aggregate(matrixWithC09Status("PASSED")))).isEmpty();
        assertThat(validate(aggregate(matrixWithC09Status("EXPERIMENTAL")))).isEmpty();
    }

    @Test
    void overallPassedWithC09FailedIsRejected() {
        // Hand-crafted fake: overall claims PASSED but C09 is FAILED. Replace the C09 row
        // (EXPERIMENTAL, empty assertions) with a valid FAILED C09 row built from a PASSED one.
        ObjectNode r = passingResult();
        ObjectNode c09Failed = FX.passedRow("C09");
        c09Failed.put("status", "FAILED");
        c09Failed.put("failureReason", "attach failed");
        ((ObjectNode) ((ArrayNode) c09Failed.path("assertions")).get(0)).put("passed", false);
        ArrayNode rowsArr = (ArrayNode) r.path("rows");
        for (int i = 0; i < rowsArr.size(); i++) {
            if ("C09".equals(rowsArr.get(i).get("scenario").asText())) {
                rowsArr.set(i, c09Failed);
                break;
            }
        }
        // overall stays PASSED (stale) -> must be caught.
        assertThat(validate(r)).anyMatch(e -> e.contains("C09")
                && (e.contains("PASSED or EXPERIMENTAL") || e.contains("overall is PASSED but C09")));
    }

    // --- row-evidence tamper rejection (correction 2) ---

    @Test
    void tamperRowCommandRejected() {
        ObjectNode r = passingResult();
        row(r, "C01").put("command", "");
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("missing command"));
    }

    @Test
    void tamperRowCatalogRejected() {
        ObjectNode r = passingResult();
        ((ObjectNode) row(r, "C01").path("catalog")).put("runnerOs", "macOS");
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("runnerOs must equal"));
    }

    @Test
    void tamperRowLoadingModeRejected() {
        ObjectNode r = passingResult();
        row(r, "C01").put("loadingMode", "agentmain");
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("loadingMode must equal"));
    }

    @Test
    void tamperRowFixtureRejected() {
        ObjectNode r = passingResult();
        row(r, "C01").put("fixture", "wrong");
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("fixture must equal"));
    }

    @Test
    void tamperRowStartedAtRejected() {
        ObjectNode r = passingResult();
        row(r, "C01").put("startedAt", "not-a-date");
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("ISO-8601 instant"));
    }

    @Test
    void tamperRowTargetJvmPidRejected() {
        ObjectNode r = passingResult();
        ((ObjectNode) row(r, "C01").path("targetJvm")).put("pid", 0);
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("child PID > 0"));
    }

    @Test
    void tamperRowTargetJvmJdkRejected() {
        ObjectNode r = passingResult();
        ((ObjectNode) row(r, "C01").path("targetJvm")).put("jdkVersion", "99.0.0");
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("not in the catalog target JDKs"));
    }

    @Test
    void tamperRowAssertionRejected() {
        ObjectNode r = passingResult();
        ((ObjectNode) ((ArrayNode) row(r, "C01").path("assertions")).get(0)).put("passed", false);
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("every assertion.passed=true"));
    }

    @Test
    void tamperRowModeRejected() {
        ObjectNode r = passingResult();
        row(r, "C01").put("mode", "rc");
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("mode must be 'pr' or 'dev'"));
    }

    @Test
    void tamperRowWorkingTreeDirtyRejected() {
        ObjectNode r = passingResult();
        row(r, "C01").put("workingTreeDirty", "yes");
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("workingTreeDirty must be boolean"));
    }

    @Test
    void tamperRowRunnerPidRejected() {
        ObjectNode r = passingResult();
        ((ObjectNode) row(r, "C01").path("environment")).put("runnerPid", 0);
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("runnerPid must be a positive integer"));
    }

    @Test
    void tamperRowChildPidEqualsRunnerPidRejected() {
        ObjectNode r = passingResult();
        ((ObjectNode) row(r, "C01").path("targetJvm"))
                .put("pid", CompatibilityRowFixtures.RUNNER_PID);
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("!= environment.runnerPid"));
    }

    @Test
    void tamperRowSupportLevelRejected() {
        ObjectNode r = passingResult();
        row(r, "C09").put("supportLevel", "FORMAL");
        assertThat(validate(r)).anyMatch(e -> e.contains("rows[") && e.contains("supportLevel must be"));
    }

    // --- summary / count consistency ---

    @Test
    void inconsistentPassedCountFails() {
        ObjectNode r = passingResult();
        ((ObjectNode) r.path("summary")).put("passed", 0);
        assertThat(validate(r)).anyMatch(e -> e.contains("summary.passed must equal 9"));
    }

    @Test
    void inconsistentTotalFails() {
        ObjectNode r = passingResult();
        ((ObjectNode) r.path("summary")).put("total", 99);
        assertThat(validate(r)).anyMatch(e -> e.contains("summary.total must equal 10"));
    }

    @Test
    void inconsistentRowsConsumedFails() {
        ObjectNode r = passingResult();
        ((ObjectNode) r.path("summary")).put("rowsConsumed", 3);
        assertThat(validate(r)).anyMatch(e -> e.contains("summary.rowsConsumed must equal 10"));
    }

    @Test
    void statusBucketsMustSumToRowsConsumed() {
        ObjectNode r = passingResult();
        ObjectNode extra = row(r, "C01").deepCopy();
        extra.put("status", "WEIRD"); // not counted in any status bucket
        ((ArrayNode) r.path("rows")).add(extra); // rowsSize=11 but statusSum=10
        assertThat(validate(r)).anyMatch(e -> e.contains("must equal rowsConsumed"));
    }

    // --- overall / formalComplete / exclusions / generatedAt ---

    @Test
    void overallPassedButFailuresNonEmptyFails() {
        ObjectNode r = passingResult();
        r.putArray("failures").add("something");
        assertThat(validate(r)).anyMatch(e -> e.contains("overall is PASSED but failures is non-empty"));
    }

    @Test
    void overallFailedButFailuresEmptyFails() {
        ObjectNode r = notRunResult();
        r.putArray("failures"); // clear
        assertThat(validate(r)).anyMatch(e -> e.contains("overall is FAILED but failures is empty"));
    }

    @Test
    void formalCompleteFalseButOverallPassedFails() {
        ObjectNode r = passingResult();
        r.put("formalComplete", false);
        assertThat(validate(r)).anyMatch(e -> e.contains("overall is PASSED but formalComplete is false"));
    }

    @Test
    void missingNonFormalExclusionsFails() {
        ObjectNode r = passingResult();
        r.remove("nonFormalExclusions");
        assertThat(validate(r)).anyMatch(e -> e.contains("nonFormalExclusions must list the section 10.2"));
    }

    @Test
    void tamperNonFormalExclusionStatusFails() {
        ObjectNode r = passingResult();
        ((ObjectNode) r.path("nonFormalExclusions").get(0)).put("status", "SUPPORTED"); // was NOT_SUPPORTED
        assertThat(validate(r)).anyMatch(e -> e.contains("nonFormalExclusions[0]"));
    }

    @Test
    void tamperNonFormalExclusionCombinationFails() {
        ObjectNode r = passingResult();
        ((ObjectNode) r.path("nonFormalExclusions").get(1)).put("combination", "AIX");
        assertThat(validate(r)).anyMatch(e -> e.contains("nonFormalExclusions[1]"));
    }

    @Test
    void generatedAtNotIsoFails() {
        ObjectNode r = passingResult();
        r.put("generatedAt", "2026/08/01 00:00:00");
        assertThat(validate(r)).anyMatch(e -> e.contains("generatedAt must be an ISO-8601 instant"));
    }

    @Test
    void generatedAtMissingFails() {
        ObjectNode r = passingResult();
        r.remove("generatedAt");
        assertThat(validate(r)).anyMatch(e -> e.contains("generatedAt must be a non-blank string"));
    }

    @Test
    void wrongCatalogVersionFails() {
        ObjectNode r = passingResult();
        r.put("catalogVersion", "v1.7-9.9");
        assertThat(validate(r)).anyMatch(e -> e.contains("catalogVersion must equal"));
    }

    @Test
    void nullResultFails() {
        assertThat(validate(null)).anyMatch(e -> e.contains("null/missing"));
    }

    @Test
    void missingRowsArrayFails() {
        ObjectNode r = passingResult();
        r.remove("rows");
        assertThat(validate(r)).anyMatch(e -> e.contains("rows must be an array"));
    }
}
