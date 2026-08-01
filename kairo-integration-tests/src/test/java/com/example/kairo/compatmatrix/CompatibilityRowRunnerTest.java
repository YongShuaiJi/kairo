package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic tests for {@link CompatibilityRowRunner} - the M3-A contract/dispatch
 * foundation. Asserts that, with no fixture implemented, every formal scenario fails
 * closed with truthful NOT_RUN evidence (exit 4) and C09 emits EXPERIMENTAL (exit 0),
 * that the produced row self-validates, carries mode/workingTreeDirty/runnerPid
 * provenance, and that the runner NEVER fabricates PASSED.
 */
class CompatibilityRowRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUILD = "0123456789abcdef0123456789abcdef01234567";
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final int RUNNER_PID = 7777;

    @TempDir
    Path tmp;

    private int run(String scenarioId, String outName, String mode, boolean dirty) throws Exception {
        Path out = tmp.resolve(outName);
        return CompatibilityRowRunner.runInProcess(
                new String[]{
                        "--scenario", scenarioId,
                        "--output", out.toString(),
                        "--build-id", BUILD,
                        "--command", "./scripts/v1.7/run-compatibility.sh --scenario " + scenarioId
                                + " --output " + out,
                        "--mode", mode,
                        "--working-tree-dirty", String.valueOf(dirty)
                },
                "Linux", "amd64", "17.0.11", RUNNER_PID, NOW);
    }

    @Test
    void formalScenarioFailsClosedWithNotRunExit4() throws Exception {
        int rc = run("C01", "C01.json", "dev", false);
        assertThat(rc).isEqualTo(CompatibilityRowRunner.EXIT_BLOCKED);
        JsonNode row = MAPPER.readTree(Files.readString(tmp.resolve("C01.json")));
        assertThat(row.get("status").asText()).isEqualTo("NOT_RUN");
        assertThat(row.get("failureReason").asText()).contains("fail-closed");
        assertThat(row.path("targetJvm").get("pid").asInt()).isZero();
        assertThat(row.path("targetJvm").get("independent").asBoolean()).isFalse();
        assertThat(row.path("assertions").isArray()).isTrue();
        assertThat(row.path("assertions")).isEmpty();
        // Provenance (corrections 3, 4).
        assertThat(row.get("mode").asText()).isEqualTo("dev");
        assertThat(row.get("workingTreeDirty").asBoolean()).isFalse();
        assertThat(row.path("environment").get("runnerPid").asInt()).isEqualTo(RUNNER_PID);
        // The produced row must self-validate clean.
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void c09EmitsExperimentalExit0() throws Exception {
        int rc = run("C09", "C09.json", "dev", false);
        assertThat(rc).isEqualTo(CompatibilityRowRunner.EXIT_OK);
        JsonNode row = MAPPER.readTree(Files.readString(tmp.resolve("C09.json")));
        assertThat(row.get("status").asText()).isEqualTo("EXPERIMENTAL");
        assertThat(row.get("supportLevel").asText()).isEqualTo("EXPERIMENTAL");
        assertThat(row.get("failureReason").asText()).contains("macOS");
        assertThat(row.path("environment").get("runnerPid").asInt()).isEqualTo(RUNNER_PID);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void prModeCleanProducesValidRow() throws Exception {
        int rc = run("C02", "C02.json", "pr", false);
        assertThat(rc).isEqualTo(CompatibilityRowRunner.EXIT_BLOCKED); // formal NOT_RUN
        JsonNode row = MAPPER.readTree(Files.readString(tmp.resolve("C02.json")));
        assertThat(row.get("mode").asText()).isEqualTo("pr");
        assertThat(row.get("workingTreeDirty").asBoolean()).isFalse();
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void devModeDirtyProducesValidRow() throws Exception {
        int rc = run("C03", "C03.json", "dev", true);
        assertThat(rc).isEqualTo(CompatibilityRowRunner.EXIT_BLOCKED);
        JsonNode row = MAPPER.readTree(Files.readString(tmp.resolve("C03.json")));
        assertThat(row.get("mode").asText()).isEqualTo("dev");
        assertThat(row.get("workingTreeDirty").asBoolean()).isTrue();
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void everyFormalScenarioProducesNotRunAndNeverPassed() {
        for (String id : List.of("C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C10")) {
            var d = CompatibilityRowRunner.dispatch(CompatibilityScenarioCatalog.scenario(id));
            assertThat(d.status()).isEqualTo("NOT_RUN");
        }
        var d09 = CompatibilityRowRunner.dispatch(CompatibilityScenarioCatalog.scenario("C09"));
        assertThat(d09.status()).isEqualTo("EXPERIMENTAL");
    }

    @Test
    void fixturesImplementedForM3BAndM3C() {
        // M3-B: C01 (premain), C02 (external attach/agentmain) and C09 (agentmain on
        // macOS arm64) are implemented. M3-C: C03 (premain) and C04 (external attach)
        // on a Spring Boot 3 executable jar. M3-D..M3-E scenarios are still not implemented.
        for (CompatibilityScenario s : CompatibilityScenarioCatalog.all()) {
            boolean expected = "C01".equals(s.id()) || "C02".equals(s.id()) || "C09".equals(s.id())
                    || "C03".equals(s.id()) || "C04".equals(s.id());
            assertThat(CompatibilityRowRunner.fixtureImplemented(s.id()))
                    .as(s.id() + " fixtureImplemented").isEqualTo(expected);
            assertThat(CompatibilityRowRunner.isSpringBootScenario(s.id()))
                    .as(s.id() + " isSpringBootScenario")
                    .isEqualTo("C03".equals(s.id()) || "C04".equals(s.id()));
        }
    }

    @Test
    void producedRowNeverClaimsPassed() {
        for (String id : List.of("C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C09", "C10")) {
            var opts = new CompatibilityCli.RunOptions(id, "/tmp/" + id + ".json", BUILD,
                    "./run-compatibility.sh --scenario " + id, "dev", false, false);
            JsonNode row = CompatibilityRowRunner.buildRow(opts, "Linux", "amd64", "17.0.11",
                    RUNNER_PID, "2026-08-01T00:00:00Z", "2026-08-01T00:00:01Z");
            assertThat(row.get("status").asText()).isNotEqualTo("PASSED");
            assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
        }
    }

    @Test
    void helpReturnsExit0() {
        int rc = CompatibilityRowRunner.runInProcess(new String[]{"--help"},
                "Linux", "amd64", "17.0.11", RUNNER_PID, NOW);
        assertThat(rc).isEqualTo(CompatibilityRowRunner.EXIT_OK);
    }

    @Test
    void unknownScenarioReturnsUsageExit1() {
        int rc = CompatibilityRowRunner.runInProcess(
                new String[]{"--scenario", "C99", "--output", "/tmp/x.json",
                        "--build-id", BUILD, "--command", "x", "--mode", "dev",
                        "--working-tree-dirty", "false"},
                "Linux", "amd64", "17.0.11", RUNNER_PID, NOW);
        assertThat(rc).isEqualTo(CompatibilityRowRunner.EXIT_USAGE);
    }

    @Test
    void exitCodeForStatusMapsCorrectly() {
        assertThat(CompatibilityRowRunner.exitCodeForStatus("PASSED")).isEqualTo(CompatibilityRowRunner.EXIT_OK);
        assertThat(CompatibilityRowRunner.exitCodeForStatus("EXPERIMENTAL")).isEqualTo(CompatibilityRowRunner.EXIT_OK);
        assertThat(CompatibilityRowRunner.exitCodeForStatus("NOT_RUN")).isEqualTo(CompatibilityRowRunner.EXIT_BLOCKED);
        assertThat(CompatibilityRowRunner.exitCodeForStatus("FAILED")).isEqualTo(CompatibilityRowRunner.EXIT_BLOCKED);
        assertThat(CompatibilityRowRunner.exitCodeForStatus("SKIPPED")).isEqualTo(CompatibilityRowRunner.EXIT_BLOCKED);
    }

    @Test
    void prModeRefusesDirtyTree() {
        // pr + dirty is rejected by the CLI parser before any row is built.
        int rc = CompatibilityRowRunner.runInProcess(
                new String[]{"--scenario", "C01", "--output", "/tmp/x.json",
                        "--build-id", BUILD, "--command", "x", "--mode", "pr",
                        "--working-tree-dirty", "true"},
                "Linux", "amd64", "17.0.11", RUNNER_PID, NOW);
        assertThat(rc).isEqualTo(CompatibilityRowRunner.EXIT_USAGE);
    }
}
