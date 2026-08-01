package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic aggregator tests (section 10.4.1). The aggregator consumes row JSON
 * only and never executes scenarios. These assert: a complete passing matrix aggregates
 * to overall=PASSED; and each fail-closed condition (empty/incomplete/duplicate/
 * unknown, wrong build id, wrong platform metadata, formal row skipped/not-run, fake
 * PASSED, malformed/unparseable) makes overall=FAILED. C09 EXPERIMENTAL is
 * non-blocking.
 */
class CompatibilityRowAggregatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CompatibilityRowFixtures FX = new CompatibilityRowFixtures(MAPPER);
    private static final CompatibilityRowAggregator.AggregatorMeta META =
            new CompatibilityRowAggregator.AggregatorMeta("2026-08-01T00:00:00Z",
                    "./scripts/v1.7/aggregate-compatibility.sh --input x --output y");

    private CompatibilityRowAggregator.AggregationOutcome aggregate(List<JsonNode> rows) {
        List<CompatibilityRowAggregator.ParsedRow> parsed = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            parsed.add(new CompatibilityRowAggregator.ParsedRow(
                    "row-" + i + ".json", rows.get(i), null));
        }
        return new CompatibilityRowAggregator(MAPPER).aggregate(parsed, META);
    }

    private CompatibilityRowAggregator.AggregationOutcome aggregateParsed(
            List<CompatibilityRowAggregator.ParsedRow> parsed) {
        return new CompatibilityRowAggregator(MAPPER).aggregate(parsed, META);
    }

    // --- legal complete matrix ---

    @Test
    void completePassingMatrixAggregatesToPassed() {
        var outcome = aggregate(FX.passingMatrix());
        assertThat(outcome.overallPassed()).isTrue();
        assertThat(outcome.result().get("overall").asText()).isEqualTo("PASSED");
        assertThat(outcome.failureReasons()).isEmpty();
        assertThat(outcome.result().get("formalComplete").asBoolean()).isTrue();
        // Single candidate build id recorded.
        assertThat(outcome.result().get("buildId").asText()).hasSize(40);
    }

    @Test
    void passingMatrixSummaryCountsAreConsistent() {
        var outcome = aggregate(FX.passingMatrix());
        JsonNode s = outcome.result().path("summary");
        assertThat(s.get("total").asInt()).isEqualTo(10);
        assertThat(s.get("formalScenarios").asInt()).isEqualTo(9);
        assertThat(s.get("experimentalScenarios").asInt()).isEqualTo(1);
        assertThat(s.get("passed").asInt()).isEqualTo(9);
        assertThat(s.get("experimental").asInt()).isEqualTo(1);
        assertThat(s.get("failed").asInt()).isZero();
        assertThat(s.get("notRun").asInt()).isZero();
        assertThat(s.get("rowsConsumed").asInt()).isEqualTo(10);
    }

    @Test
    void c09ExperimentalIsNonBlockingWhenFormalRowsPassed() {
        var outcome = aggregate(FX.passingMatrix()); // C09 is EXPERIMENTAL here
        assertThat(outcome.overallPassed()).isTrue();
        JsonNode c09 = findRow(outcome.result(), "C09");
        assertThat(c09.get("status").asText()).isEqualTo("EXPERIMENTAL");
    }

    @Test
    void c09PassedIsAlsoNonBlocking() {
        // C09 may be PASSED (real macOS CI) without affecting the formal gate.
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        rows.removeIf(r -> "C09".equals(r.get("scenario").asText()));
        rows.add(FX.passedRow("C09"));
        var outcome = aggregate(rows);
        assertThat(outcome.overallPassed()).isTrue();
        assertThat(findRow(outcome.result(), "C09").get("status").asText()).isEqualTo("PASSED");
    }

    // --- C09 completion semantics (correction 1 / section 10.5) ---
    // C09 must be PASSED or EXPERIMENTAL; FAILED/SKIPPED/NOT_RUN make overall FAILED.

    @Test
    void c09FailedMakesOverallFailed() {
        var outcome = aggregate(matrixWithC09Status("FAILED"));
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons())
                .anyMatch(e -> e.contains("C09 is FAILED") && e.contains("PASSED or EXPERIMENTAL"));
    }

    @Test
    void c09SkippedMakesOverallFailed() {
        var outcome = aggregate(matrixWithC09Status("SKIPPED"));
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons())
                .anyMatch(e -> e.contains("C09 is SKIPPED") && e.contains("PASSED or EXPERIMENTAL"));
    }

    @Test
    void c09NotRunMakesOverallFailed() {
        var outcome = aggregate(matrixWithC09Status("NOT_RUN"));
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons())
                .anyMatch(e -> e.contains("C09 is NOT_RUN") && e.contains("PASSED or EXPERIMENTAL"));
    }

    @Test
    void c09ExperimentalRemainsNonBlocking() {
        var outcome = aggregate(matrixWithC09Status("EXPERIMENTAL"));
        assertThat(outcome.overallPassed()).isTrue();
    }

    private List<JsonNode> matrixWithC09Status(String status) {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        rows.removeIf(r -> "C09".equals(r.get("scenario").asText()));
        ObjectNode c09 = FX.passedRow("C09");
        switch (status) {
            case "PASSED" -> { }
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

    // --- empty / incomplete matrix ---

    @Test
    void emptyMatrixFailsClosed() {
        var outcome = aggregate(List.of());
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("missing row for scenario C01"));
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("missing row for scenario C10"));
    }

    @Test
    void incompleteMatrixFailsClosed() {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        rows.removeIf(r -> "C05".equals(r.get("scenario").asText()));
        var outcome = aggregate(rows);
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("missing row for scenario C05"));
    }

    // --- duplicate / unknown ---

    @Test
    void duplicateRowFailsClosed() {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        rows.add(FX.passedRow("C01")); // duplicate C01
        var outcome = aggregate(rows);
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("duplicate row for scenario C01"));
    }

    @Test
    void unknownScenarioFailsClosed() {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        ObjectNode bogus = FX.passedRow("C01");
        bogus.put("scenario", "C42");
        rows.add(bogus);
        var outcome = aggregate(rows);
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("unknown scenario: C42"));
    }

    // --- wrong build id ---

    @Test
    void buildIdMismatchFailsClosed() {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        ((ObjectNode) rows.get(0)).put("buildId", CompatibilityRowFixtures.OTHER_BUILD);
        var outcome = aggregate(rows);
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("buildId mismatch"));
    }

    // --- wrong platform / load metadata (a PASSED row that the row validator rejects) ---

    @Test
    void wrongPlatformMetadataOnPassedRowFailsClosed() {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        ((ObjectNode) ((ObjectNode) findRow0(rows, "C01")).path("environment")).put("osArch", "aarch64");
        var outcome = aggregate(rows);
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("malformed row")
                && e.contains("runner arch"));
    }

    @Test
    void catalogMismatchOnRowFailsClosed() {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        ((ObjectNode) ((ObjectNode) findRow0(rows, "C01")).path("catalog")).put("runnerOs", "macOS");
        var outcome = aggregate(rows);
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("malformed row"));
    }

    // --- formal row skipped / not-run ---

    @Test
    void formalRowSkippedFailsClosed() {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        ObjectNode c01 = (ObjectNode) findRow0(rows, "C01");
        c01.put("status", "SKIPPED");
        c01.put("failureReason", "skipped");
        var outcome = aggregate(rows);
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("formal scenario C01 is SKIPPED"));
    }

    @Test
    void m3aNotRunMatrixFailsClosed() {
        // The actual M3-A state: every formal row NOT_RUN, C09 EXPERIMENTAL.
        var outcome = aggregate(FX.m3aNotRunMatrix());
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.result().get("overall").asText()).isEqualTo("FAILED");
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("formal scenario C01 is NOT_RUN"));
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("formal scenario C10 is NOT_RUN"));
        // C09 EXPERIMENTAL is NOT itself a failure.
        assertThat(outcome.failureReasons()).noneMatch(e -> e.contains("C09 is EXPERIMENTAL"));
    }

    // --- fake PASSED ---

    @Test
    void fakePassedEvidenceFailsClosed() {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        ((ObjectNode) ((ObjectNode) findRow0(rows, "C01")).path("targetJvm")).put("pid", 0);
        var outcome = aggregate(rows);
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("malformed row") && e.contains("PID > 0"));
    }

    // --- malformed / unparseable ---

    @Test
    void malformedRowFailsClosed() {
        List<JsonNode> rows = new ArrayList<>(FX.passingMatrix());
        ObjectNode bad = MAPPER.createObjectNode();
        bad.put("scenario", "C01");
        bad.put("status", "PASSED");
        rows.set(0, bad);
        var outcome = aggregate(rows);
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("malformed row"));
    }

    @Test
    void unparseableFileFailsClosed() {
        List<CompatibilityRowAggregator.ParsedRow> parsed = new ArrayList<>();
        for (JsonNode r : FX.passingMatrix()) {
            parsed.add(new CompatibilityRowAggregator.ParsedRow(r.get("scenario").asText() + ".json", r, null));
        }
        // Replace C02's parsed row with an unparseable one.
        parsed.removeIf(p -> "C02.json".equals(p.fileName()));
        parsed.add(new CompatibilityRowAggregator.ParsedRow("C02.json", null, "unparseable JSON (test): oops"));
        var outcome = aggregateParsed(parsed);
        assertThat(outcome.overallPassed()).isFalse();
        assertThat(outcome.failureReasons()).anyMatch(e -> e.contains("C02.json") && e.contains("unparseable"));
    }

    // --- result provenance ---

    @Test
    void resultCarriesNonFormalExclusions() {
        var outcome = aggregate(FX.passingMatrix());
        Map<String, String> excl = outcome.result().path("nonFormalExclusions").findValuesAsText("combination")
                .stream().collect(Collectors.toMap(k -> k, k -> k));
        assertThat(excl).containsKey("JDK 8/11 目标 JVM");
        assertThat(outcome.result().path("nonFormalExclusions").size())
                .isEqualTo(CompatibilityScenarioCatalog.nonFormalExclusions().size());
    }

    @Test
    void resultSelfValidatesCleanForPassingMatrix() {
        // The aggregator's output must itself pass the result validator.
        var outcome = aggregate(FX.passingMatrix());
        assertThat(new CompatibilityResultValidator().validate(outcome.result())).isEmpty();
    }

    @Test
    void resultSelfValidatesCleanForNotRunMatrix() {
        // Even a FAILED aggregate must be structurally valid (verifier-acceptable).
        var outcome = aggregate(FX.m3aNotRunMatrix());
        assertThat(new CompatibilityResultValidator().validate(outcome.result())).isEmpty();
    }

    private static JsonNode findRow(JsonNode result, String scenarioId) {
        for (JsonNode r : result.path("rows")) {
            if (r.get("scenario").asText().equals(scenarioId)) {
                return r;
            }
        }
        throw new AssertionError("row not found: " + scenarioId);
    }

    private static JsonNode findRow0(List<JsonNode> rows, String scenarioId) {
        for (JsonNode r : rows) {
            if (r.get("scenario").asText().equals(scenarioId)) {
                return r;
            }
        }
        throw new AssertionError("row not found: " + scenarioId);
    }
}
