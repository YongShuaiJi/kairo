package com.example.kairo.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResultValidatorTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> IDS = ScenarioCatalog.ids();
    private final ResultValidator validator = new ResultValidator();

    // 40-hex commit IDs (peeled from annotated tags).
    private static final String HEX40A = "113823b41981a2d8fb5473a772ae2d2938d9582e";
    private static final String HEX40B = "b29683c4b50681298d2a462c8da4ec982c9cf2cf";

    @Test
    void validPrResultProducesNoErrors() {
        List<String> errors = validator.validate(validResult("pr", 5, false), IDS);
        assertThat(errors).isEmpty();
    }

    @Test
    void validSmokeResultWithDirtyCandidateProducesNoErrors() {
        List<String> errors = validator.validate(validResult("smoke", 1, true), IDS);
        assertThat(errors).isEmpty();
    }

    @Test
    void wrongSchemaVersionIsReported() {
        ObjectNode r = validResult("pr", 5, false);
        r.put("schemaVersion", "2.0");
        assertThat(validator.validate(r, IDS)).anyMatch(e -> e.contains("schemaVersion must be exactly"));
    }

    @Test
    void nonHexBuildIdIsReported() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("builds").get("baseline")).put("resolvedBuildId", "4776809d");
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("40-hex commit id"));
    }

    @Test
    void placeholderBuildCommandIsRejected() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("builds").get("baseline")).put("buildCommand", "mvn -cp <baseline-impl>");
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("buildCommand must not contain placeholders"));
    }

    @Test
    void placeholderHarnessCommandIsRejected() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("builds").get("candidate")).put("harnessCommand", "java ... candidate");
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("harnessCommand must not contain placeholders"));
    }

    @Test
    void prModeWithFewerThanFiveForksIsReported() {
        ObjectNode r = validResult("pr", 4, false);
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("PR mode requires >= 5 forks"));
    }

    @Test
    void prModeWithDirtyCandidateIsReported() {
        ObjectNode r = validResult("pr", 5, true);
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("candidateWorkingTreeDirty must be false"));
    }

    @Test
    void missingEnvironmentIsReported() {
        ObjectNode r = validResult("pr", 5, false);
        r.remove("environment");
        assertThat(validator.validate(r, IDS)).anyMatch(e -> e.contains("missing environment"));
    }

    @Test
    void missingScenarioBreaksCompleteness() {
        ObjectNode r = validResult("pr", 5, false);
        ArrayNode scenarios = (ArrayNode) r.get("scenarios");
        String removed = scenarios.get(0).get("id").asText();
        scenarios.remove(0);
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("missing scenario in result: " + removed));
    }

    @Test
    void unknownScenarioIdIsReported() {
        ObjectNode r = validResult("pr", 5, false);
        ArrayNode scenarios = (ArrayNode) r.get("scenarios");
        ObjectNode extra = scenarios.addObject();
        extra.put("id", "not-in-catalog");
        extra.put("gated", true);
        extra.set("baseline", statsObject(5));
        extra.set("candidate", statsObject(5));
        extra.putObject("comparison").put("verdict", "PASS").put("gated", true);
        assertThat(validator.validate(r, IDS)).anyMatch(e -> e.contains("not in catalog: not-in-catalog"));
    }

    @Test
    void badComparisonVerdictIsReported() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("scenarios").get(3).get("comparison")).put("verdict", "MAYBE");
        assertThat(validator.validate(r, IDS)).anyMatch(e -> e.contains("comparison.verdict invalid"));
    }

    @Test
    void notGatedVerdictIsAccepted() {
        ObjectNode r = validResult("pr", 5, false);
        // no-agent-baseline is observed-only -> NOT_GATED (already set by validResult)
        assertThat(validator.validate(r, IDS)).isEmpty();
        // Verify the observed-only scenario actually carries NOT_GATED in the fixture.
        boolean found = false;
        for (com.fasterxml.jackson.databind.JsonNode sc : r.get("scenarios")) {
            if (sc.get("id").asText().equals("no-agent-baseline")) {
                assertThat(sc.get("comparison").get("verdict").asText()).isEqualTo("NOT_GATED");
                found = true;
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void budgetPassedWithNonEmptyFailedScenariosIsInconsistent() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("budget")).put("passed", true);
        ((ArrayNode) r.get("budget").get("failedScenarios")).add("before-hit");
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("budget.passed=true but failedScenarios is non-empty"));
    }

    @Test
    void budgetPassedWithNonComparableGatedScenariosIsInconsistent() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("budget")).put("passed", true);
        ((ArrayNode) r.get("budget").get("nonComparableGatedScenarios")).add("before-hit");
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("nonComparableGatedScenarios is non-empty"));
    }

    @Test
    void scenarioMissingStatsIsReported() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("scenarios").get(3)).remove("baseline");
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("missing baseline stats"));
    }

    @Test
    void scenarioWithNonPositiveMedianIsReported() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("scenarios").get(3).get("baseline")).put("median", 0.0);
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("median must be positive finite"));
    }

    @Test
    void scenarioWithInfiniteP95IsReported() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("scenarios").get(3).get("candidate")).put("p95", Double.POSITIVE_INFINITY);
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("p95 must be positive finite"));
    }

    @Test
    void inconsistentForkCountIsReported() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("scenarios").get(3).get("baseline")).put("forkCount", 4);
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains("forkCount=4 != harness.forks=5"));
    }

    @Test
    void sampleCountMustEqualForksTimesMeasurementIterations() {
        ObjectNode r = validResult("pr", 5, false);
        ((ObjectNode) r.get("scenarios").get(3).get("baseline")).put("sampleCount", 99);
        assertThat(validator.validate(r, IDS))
                .anyMatch(e -> e.contains(
                        "sampleCount=99 != expected forks*measurementIterations=100"));
    }

    @Test
    void jvmArgsMustBeNonEmptyArray() {
        ObjectNode r = validResult("pr", 5, false);
        r.putArray("jvmArgs");
        assertThat(validator.validate(r, IDS)).anyMatch(e -> e.contains("jvmArgs"));
    }

    @Test
    void nullResultIsReported() {
        assertThat(validator.validate(null, IDS)).containsExactly("result is null");
    }

    // ------------------------------------------------------------------ helpers

    private static ObjectNode statsObject(int forks) {
        ObjectNode s = M.createObjectNode();
        s.put("median", 100.0);
        s.put("p95", 120.0);
        s.put("p99", 130.0);
        s.put("mean", 100.0);
        s.put("stddev", 5.0);
        s.put("dispersion", 0.05);
        s.put("sampleCount", forks * 20);
        s.put("ops", 20000);
        s.put("forkCount", forks);
        return s;
    }

    private static ObjectNode validResult(String mode, int forks, boolean candDirty) {
        ObjectNode r = M.createObjectNode();
        r.put("schemaVersion", "1.0");
        r.put("mode", mode);
        r.put("budgetFile", "v1.7-performance-budget.json");
        r.put("budgetDirection", "regression-vs-baseline");
        r.put("units", "ns-per-op");
        r.put("metricDirection", "lower-is-better");

        ObjectNode env = r.putObject("environment");
        env.put("jdkVersion", "21");
        env.put("osName", "Linux");
        env.put("osArch", "amd64");
        env.put("availableProcessors", 8);

        ArrayNode jvm = r.putArray("jvmArgs");
        jvm.add("-Xmx512m");

        ObjectNode harness = r.putObject("harness");
        harness.put("mainClass", "com.example.kairo.perf.HarnessMain");
        harness.put("forks", forks);
        harness.put("warmupIterations", 5);
        harness.put("measurementIterations", 20);
        harness.put("candidateWorkingTreeDirty", candDirty);

        ObjectNode builds = r.putObject("builds");
        builds.set("baseline", buildNode("V1.6.0", HEX40A, "V1.6.0"));
        builds.set("candidate", buildNode("HEAD", HEX40B, "HEAD"));

        ArrayNode scenarios = r.putArray("scenarios");
        for (String id : IDS) {
            ObjectNode sc = scenarios.addObject();
            sc.put("id", id);
            sc.put("category", "test");
            sc.put("description", "desc");
            sc.put("comparable", true);
            boolean gated = ScenarioCatalog.get(id).gated();
            sc.put("gated", gated);
            sc.put("opsLabel", "call");
            sc.put("opsPerIteration", 20000);
            sc.set("baseline", statsObject(forks));
            sc.set("candidate", statsObject(forks));
            ObjectNode cmp = sc.putObject("comparison");
            cmp.put("gated", gated);
            cmp.put("verdict", gated ? "PASS" : "NOT_GATED");
            if (gated) {
                cmp.put("medianRegressionPct", 0.0);
                cmp.put("p95RegressionPct", 0.0);
            }
        }
        r.set("scenarios", scenarios);

        ObjectNode budget = r.putObject("budget");
        budget.put("passed", true);
        budget.putArray("failedScenarios");
        budget.putArray("nonComparableGatedScenarios");
        budget.putArray("notGatedScenarios");
        return r;
    }

    private static ObjectNode buildNode(String label, String id, String ref) {
        ObjectNode b = M.createObjectNode();
        b.put("label", label);
        b.put("resolvedBuildId", id);
        b.put("sourceRef", ref);
        b.put("buildCommand", "cd /tmp/worktree && mvn -B -ntp -pl kairo-integration-tests -am test-compile");
        b.put("harnessCommand", "java -Xmx512m -cp /cp com.example.kairo.perf.HarnessMain --scenario s --warmup 5");
        b.put("classpath", "/cp:/repo/kairo-core/target/classes");
        return b;
    }
}
