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
 * End-to-end tests for {@link CompatibilityVerifierMain}: it validates an existing
 * compatibility-result.json and maps to the exact exit code. Exit 0 only when the
 * result is structurally valid AND overall=PASSED; valid-but-FAILED (the M3-A state)
 * exits 4; malformed JSON exits 6; a missing file exits 1.
 */
class CompatibilityVerifierMainTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CompatibilityRowFixtures FX = new CompatibilityRowFixtures(MAPPER);
    private static final CompatibilityRowAggregator.AggregatorMeta META =
            new CompatibilityRowAggregator.AggregatorMeta("2026-08-01T00:00:00Z",
                    "./aggregate-compatibility.sh --input x --output y");

    @TempDir
    Path tmp;

    private ObjectNode aggregate(Iterable<JsonNode> rows) {
        java.util.List<CompatibilityRowAggregator.ParsedRow> parsed = new java.util.ArrayList<>();
        int i = 0;
        for (JsonNode r : rows) {
            parsed.add(new CompatibilityRowAggregator.ParsedRow("row-" + (i++) + ".json", r, null));
        }
        return new CompatibilityRowAggregator(MAPPER).aggregate(parsed, META).result();
    }

    private Path write(JsonNode node, String name) throws Exception {
        Path p = tmp.resolve(name);
        Files.writeString(p, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        return p;
    }

    private int run(Path file) {
        return CompatibilityVerifierMain.runInProcess(new String[]{file.toString()});
    }

    @Test
    void validPassedResultExits0() throws Exception {
        Path file = write(aggregate(FX.passingMatrix()), "result.json");
        assertThat(run(file)).isZero();
    }

    @Test
    void validFailedResultExits4() throws Exception {
        // The M3-A state: structurally valid but overall=FAILED.
        Path file = write(aggregate(FX.m3aNotRunMatrix()), "result.json");
        assertThat(run(file)).isEqualTo(4);
    }

    @Test
    void malformedJsonExits6() throws Exception {
        Path file = tmp.resolve("bad.json");
        Files.writeString(file, "{not valid json");
        assertThat(run(file)).isEqualTo(6);
    }

    @Test
    void missingFileExits1() {
        int rc = CompatibilityVerifierMain.runInProcess(new String[]{tmp.resolve("nope.json").toString()});
        assertThat(rc).isEqualTo(1);
    }

    @Test
    void structurallyInvalidResultExits4() throws Exception {
        ObjectNode r = aggregate(FX.passingMatrix());
        r.remove("rows"); // breaks schema
        assertThat(run(write(r, "result.json"))).isEqualTo(4);
    }

    @Test
    void missingCatalogVersionExits4() throws Exception {
        ObjectNode r = aggregate(FX.passingMatrix());
        r.put("catalogVersion", "v9.9");
        assertThat(run(write(r, "result.json"))).isEqualTo(4);
    }

    @Test
    void helpExits0() {
        assertThat(CompatibilityVerifierMain.runInProcess(new String[]{"--help"})).isZero();
    }

    @Test
    void noArgExits1() {
        assertThat(CompatibilityVerifierMain.runInProcess(new String[]{})).isEqualTo(1);
    }
}
