package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for {@link CompatibilityAggregatorMain}: it reads a directory of
 * row JSON, aggregates to the single compatibility-result.json, self-validates and
 * maps the outcome to the exact exit code. Covers the file I/O path the pure
 * {@link CompatibilityRowAggregator} tests do not.
 */
class CompatibilityAggregatorMainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CompatibilityRowFixtures FX = new CompatibilityRowFixtures(MAPPER);

    @TempDir
    Path tmp;

    private Path writeRows(String dirName, Iterable<JsonNode> rows) throws Exception {
        Path dir = tmp.resolve(dirName);
        Files.createDirectories(dir);
        for (JsonNode r : rows) {
            Files.writeString(dir.resolve(r.get("scenario").asText() + ".json"),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(r));
        }
        return dir;
    }

    private int run(String[] args) {
        return CompatibilityAggregatorMain.runInProcess(args, java.time.Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void passingMatrixAggregatesExit0() throws Exception {
        Path dir = writeRows("pass", FX.passingMatrix());
        Path out = tmp.resolve("result.json");
        int rc = run(new String[]{"--input", dir.toString(), "--output", out.toString(),
                "--command", "./aggregate-compatibility.sh --input x --output y"});
        assertThat(rc).isZero();
        assertThat(Files.isRegularFile(out)).isTrue();
        JsonNode result = MAPPER.readTree(Files.readString(out));
        assertThat(result.get("overall").asText()).isEqualTo("PASSED");
        assertThat(result.get("formalComplete").asBoolean()).isTrue();
        // The produced result passes the verifier's structural validation.
        assertThat(new CompatibilityResultValidator().validate(result)).isEmpty();
    }

    @Test
    void notRunMatrixAggregatesExit4ButWritesValidResult() throws Exception {
        Path dir = writeRows("notrun", FX.m3aNotRunMatrix());
        Path out = tmp.resolve("result.json");
        int rc = run(new String[]{"--input", dir.toString(), "--output", out.toString(),
                "--command", "./aggregate-compatibility.sh --input x --output y"});
        assertThat(rc).isEqualTo(4);
        JsonNode result = MAPPER.readTree(Files.readString(out));
        assertThat(result.get("overall").asText()).isEqualTo("FAILED");
        assertThat(result.path("failures").size()).isPositive();
        // A FAILED aggregate is still structurally valid (verifier-acceptable).
        assertThat(new CompatibilityResultValidator().validate(result)).isEmpty();
    }

    @Test
    void emptyDirectoryAggregatesExit4() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("empty"));
        Path out = tmp.resolve("result.json");
        int rc = run(new String[]{"--input", dir.toString(), "--output", out.toString(),
                "--command", "./aggregate-compatibility.sh --input x --output y"});
        assertThat(rc).isEqualTo(4);
    }

    @Test
    void missingInputDirectoryExitsUnusable() throws Exception {
        Path out = tmp.resolve("result.json");
        int rc = run(new String[]{"--input", tmp.resolve("does-not-exist").toString(),
                "--output", out.toString(), "--command", "x"});
        assertThat(rc).isEqualTo(3);
    }

    @Test
    void unparseableFileAggregatesExit4() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("bad"));
        Files.writeString(dir.resolve("C01.json"), "{not json");
        int rc = run(new String[]{"--input", dir.toString(), "--output", tmp.resolve("r.json").toString(),
                "--command", "x"});
        assertThat(rc).isEqualTo(4);
    }

    @Test
    void duplicateRowsAggregatesExit4() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("dup"));
        ObjectNode c01 = FX.passedRow("C01");
        Files.writeString(dir.resolve("C01.json"), MAPPER.writeValueAsString(c01));
        Files.writeString(dir.resolve("C01-dup.json"), MAPPER.writeValueAsString(c01));
        int rc = run(new String[]{"--input", dir.toString(), "--output", tmp.resolve("r.json").toString(),
                "--command", "x"});
        assertThat(rc).isEqualTo(4);
    }

    @Test
    void helpExits0() {
        assertThat(run(new String[]{"--help"})).isZero();
    }

    @Test
    void missingRequiredArgsExits1() {
        assertThat(run(new String[]{"--input", tmp.toString()})).isEqualTo(1);
    }

    @Test
    void fakePassedRowAggregatesExit4() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("fake"));
        ObjectNode c01 = FX.passedRow("C01");
        ((ObjectNode) c01.path("targetJvm")).put("pid", 0); // fake: PASSED with no real pid
        Files.writeString(dir.resolve("C01.json"), MAPPER.writeValueAsString(c01));
        int rc = run(new String[]{"--input", dir.toString(), "--output", tmp.resolve("r.json").toString(),
                "--command", "x"});
        assertThat(rc).isEqualTo(4);
    }
}
