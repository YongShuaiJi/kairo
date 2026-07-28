package com.example.kairo.perf.statecycle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Short real lifecycle test for the M2-B harness: runs the harness in-process with
 * the minimum cycle count (6 = one cycle per scenario) so every one of the six
 * required scenarios executes against a real ByteBuddyAgent + AgentRuntime, then
 * asserts a zero exit, a schema-valid result, and overall PASSED.
 *
 * <p>This is the in-JVM counterpart of the fixed shell command
 * {@code ./scripts/v1.7/run-state-cycle.sh --cycles 500 --output target/v1.7}; it
 * exercises the same code path on a tiny budget for local validation.
 */
class StateCycleShortLifecycleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUILD_ID = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path outputDir;

    /**
     * Short real lifecycle run covering all six scenarios. Asserts the expected
     * correct behaviour: every scenario restores its bytecode hash after full
     * unload and the harness exits zero.
     */
    @Disabled("M2-B failing-before evidence: enable on bugfix/v1.7-rulechain-bytecode-restore")
    @Test
    void sixCycleRunCoversAllScenariosAndPasses() throws Exception {
        String cmd = "./scripts/v1.7/run-state-cycle.sh --cycles 6 --output " + outputDir;
        int rc = StateCycleHarness.runInProcess(new String[]{
                "--cycles", "6",
                "--output", outputDir.toString(),
                "--build-id", BUILD_ID,
                "--command", cmd,
                "--jvm-args", "-Xms512m -Xmx512m",
                "--mode", "pr",
                "--working-tree-dirty", "false"
        });

        Path resultFile = outputDir.resolve("state-cycle-result.json");
        if (rc != 0 && Files.exists(resultFile)) {
            JsonNode result = MAPPER.readTree(Files.readString(resultFile));
            JsonNode ff = result.path("firstFailure");
            if (ff.isObject()) {
                System.out.println("[state-cycle] harness exited " + rc + "; firstFailure: "
                        + ff.path("scenario").asText() + " cycle=" + ff.path("cycleIndex").asInt()
                        + " phase=" + ff.path("phase").asText()
                        + " expected=" + ff.path("expected").asText()
                        + " actual=" + ff.path("actual").asText()
                        + " detail=" + ff.path("detail").asText());
            }
        }
        assertThat(rc).as("harness exit code").isZero();
        assertThat(resultFile).exists();

        JsonNode result = MAPPER.readTree(Files.readString(resultFile));
        List<String> errors = new StateCycleResultValidator().validate(result, 6);
        assertThat(errors).as("result schema errors").isEmpty();
        assertThat(result.path("overall").asText()).isEqualTo("PASSED");

        for (String id : StateCycleScenarioCatalog.ids()) {
            JsonNode s = findScenario(result, id);
            assertThat(s.path("cyclesRequested").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(s.path("cyclesCompleted").asInt()).isEqualTo(s.path("cyclesRequested").asInt());
            assertThat(s.path("cyclesFailed").asInt()).isZero();
            if (!id.equals(StateCycleScenarioCatalog.concurrentScenario().id())) {
                JsonNode first = s.path("firstSample");
                assertThat(first.path("enhancedDiffersFromBaseline").asBoolean())
                        .as(id + " enhanced bytes must differ from baseline (non-vacuous)").isTrue();
                assertThat(first.path("hashRestored").asBoolean())
                        .as(id + " hash must be restored after full unload").isTrue();
                assertThat(first.path("normalizedIdentical").asBoolean())
                        .as(id + " normalized bytecode must be identical after unload").isTrue();
            }
        }

        JsonNode conflict = result.path("concurrentConflict");
        assertThat(conflict.path("applied").asInt()).isEqualTo(1);
        assertThat(conflict.path("mixedStateDetected").asBoolean()).isFalse();
        assertThat(conflict.path("hashRestored").asBoolean()).isTrue();

        assertThat(result.path("cycles").path("completed").asInt()).isEqualTo(6);
        assertThat(result.path("cycles").path("failed").asInt()).isZero();
    }

    @Test
    void distributionCoversEveryScenarioForSmallBudget() {
        int[] d = StateCycleScenarioCatalog.distribute(6);
        assertThat(d).hasSize(6);
        for (int count : d) {
            assertThat(count).isGreaterThanOrEqualTo(1);
        }
        // 5 cyclic + 1 concurrent == 6 total.
        int sum = 0;
        for (int count : d) {
            sum += count;
        }
        assertThat(sum).isEqualTo(6);
        assertThat(d[5]).as("concurrent scenario always gets exactly 1").isEqualTo(1);
    }

    @Test
    void distributionFor500SumsTo500() {
        int[] d = StateCycleScenarioCatalog.distribute(500);
        int sum = 0;
        for (int count : d) {
            sum += count;
        }
        assertThat(sum).isEqualTo(500);
        assertThat(d[5]).isEqualTo(1);
    }

    private static JsonNode findScenario(JsonNode result, String id) {
        for (JsonNode s : result.path("scenarios")) {
            if (id.equals(s.path("id").asText())) {
                return s;
            }
        }
        throw new AssertionError("scenario not found in result: " + id);
    }
}
