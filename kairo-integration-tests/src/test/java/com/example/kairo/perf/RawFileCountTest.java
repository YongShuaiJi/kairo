package com.example.kairo.perf;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic verification of exact expected raw-file / fork / sample counts
 * (M2-A correction 3). The reporter's per-side raw fork count must equal the number
 * of valid (non-error) fork files for a scenario; stale or extra fork files must
 * not be silently folded into the sample set.
 *
 * <p>This test writes fixture raw files (with fixture timings — no wall-clock) into
 * a temp dir and invokes the reporter's aggregation directly via a small adapter,
 * then checks the resulting per-scenario forkCount/sampleCount. It does NOT run the
 * shell script.
 */
class RawFileCountTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final List<String> IDS = ScenarioCatalog.ids();

    @TempDir
    Path tmp;

    @Test
    void exactForkAndSampleCountsAreRecorded() throws Exception {
        File baseDir = tmp.resolve("base").toFile();
        File candDir = tmp.resolve("cand").toFile();
        baseDir.mkdirs();
        candDir.mkdirs();
        // 5 forks x 20 samples each, all 14 scenarios, both sides.
        for (String id : IDS) {
            for (int f = 1; f <= 5; f++) {
                writeRaw(baseDir, id, f, 20);
                writeRaw(candDir, id, f, 20);
            }
        }
        File output = tmp.resolve("benchmark-result.json").toFile();
        int status = ReporterHarness.run(baseDir, candDir, output);
        assertThat(status).isZero();

        ObjectNode result = (ObjectNode) M.readTree(output);
        for (com.fasterxml.jackson.databind.JsonNode sc : result.get("scenarios")) {
            assertThat(sc.get("baseline").get("forkCount").asInt()).isEqualTo(5);
            assertThat(sc.get("candidate").get("forkCount").asInt()).isEqualTo(5);
            assertThat(sc.get("baseline").get("sampleCount").asInt()).isEqualTo(5 * 20);
            assertThat(sc.get("candidate").get("sampleCount").asInt()).isEqualTo(5 * 20);
        }
        assertThat(result.get("harness").get("forks").asInt()).isEqualTo(5);
    }

    @Test
    void staleForkFromPriorRunProducesInconsistentForkCount() throws Exception {
        // Simulate a prior 5-fork run leaving fork2..5 behind, then a 1-fork smoke that
        // only writes fork1: the per-side forkCount (5) must NOT match harness.forks (1).
        File baseDir = tmp.resolve("base").toFile();
        File candDir = tmp.resolve("cand").toFile();
        baseDir.mkdirs();
        candDir.mkdirs();
        // Prior run: 5 forks for before-hit on baseline.
        for (int f = 1; f <= 5; f++) {
            writeRaw(baseDir, "before-hit", f, 5);
        }
        // Current 1-fork smoke: only fork1 for the other scenarios (baseline + candidate).
        for (String id : IDS) {
            if (!id.equals("before-hit")) {
                writeRaw(baseDir, id, 1, 5);
            }
            writeRaw(candDir, id, 1, 5);
        }
        // before-hit baseline now has 5 forks but candidate has 1; harness.forks=1.
        File output = tmp.resolve("benchmark-result.json").toFile();
        int status = ReporterHarness.run(baseDir, candDir, output);
        // Schema validation must catch the fork-count inconsistency.
        assertThat(status).isEqualTo(6);
    }

    @Test
    void errorMarkerForkIsNotCountedAsValid() throws Exception {
        File baseDir = tmp.resolve("base").toFile();
        File candDir = tmp.resolve("cand").toFile();
        baseDir.mkdirs();
        candDir.mkdirs();
        for (String id : IDS) {
            writeRaw(baseDir, id, 1, 5);
            writeRaw(candDir, id, 1, 5);
        }
        // Overwrite one baseline fork with an error marker.
        File errFile = new File(baseDir, IDS.get(3) + "-fork1.json");
        ObjectNode err = M.createObjectNode();
        err.put("error", "correctness check failed");
        err.put("scenario", IDS.get(3));
        M.writeValue(errFile, err);

        File output = tmp.resolve("benchmark-result.json").toFile();
        int status = ReporterHarness.run(baseDir, candDir, output);
        // Harness error -> reporter exit 5.
        assertThat(status).isEqualTo(5);
    }

    private static void writeRaw(File dir, String scenario, int fork, int samples) throws Exception {
        ObjectNode root = M.createObjectNode();
        root.put("scenario", scenario);
        root.put("fork", fork);
        root.put("buildId", "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef");
        root.put("buildLabel", "test");
        root.put("opsPerIteration", 20000);
        root.put("opsLabel", "call");
        root.put("warmupIterations", 2);
        root.put("measurementIterations", samples);
        ArrayNode arr = root.putArray("samples");
        for (int i = 0; i < samples; i++) {
            arr.add(100.0 + i);
        }
        M.writeValue(new File(dir, scenario + "-fork" + fork + ".json"), root);
    }
}
