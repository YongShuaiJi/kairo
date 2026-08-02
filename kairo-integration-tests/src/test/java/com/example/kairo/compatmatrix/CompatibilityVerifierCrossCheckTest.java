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
 * End-to-end tests for the M3-F strengthened {@link CompatibilityVerifierMain}: when
 * {@code --doc} and {@code --manifest} are supplied it cross-checks the aggregate, the
 * generated document and the acceptance manifest together and maps the result to the exact
 * exit code. Exit 0 only when all three agree AND overall=PASSED; any divergence, or a
 * non-passing matrix, exits 4. The single-arg form (no cross-check) is preserved.
 */
class CompatibilityVerifierCrossCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CompatibilityRowFixtures FX = new CompatibilityRowFixtures(MAPPER);
    private static final CompatibilityRowAggregator.AggregatorMeta META =
            new CompatibilityRowAggregator.AggregatorMeta("2026-08-01T00:00:00Z",
                    "./aggregate-compatibility.sh --input x --output y");

    @TempDir
    Path tmp;

    private ObjectNode aggregate(java.util.List<JsonNode> rows) {
        java.util.List<CompatibilityRowAggregator.ParsedRow> parsed = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            parsed.add(new CompatibilityRowAggregator.ParsedRow("row-" + i + ".json", rows.get(i), null));
        }
        return new CompatibilityRowAggregator(MAPPER).aggregate(parsed, META).result();
    }

    private Path writeResult(ObjectNode result) throws Exception {
        Path p = tmp.resolve("result.json");
        Files.writeString(p, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        return p;
    }

    private Path writeDoc(ObjectNode result) throws Exception {
        Path p = tmp.resolve("v1.7.md");
        Files.writeString(p, CompatibilityDocumentGenerator.generate(result));
        return p;
    }

    private Path writeManifest(ObjectNode manifest) throws Exception {
        Path p = tmp.resolve("manifest.json");
        Files.writeString(p, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
        return p;
    }

    private ObjectNode committedManifest() throws Exception {
        return (ObjectNode) MAPPER.readTree(Files.readString(CompatibilityRepoPaths.acceptanceManifest()));
    }

    private ObjectNode setGate(ObjectNode manifest, String gate, String status) {
        ObjectNode m = manifest.deepCopy();
        for (JsonNode r : m.path("requirements")) {
            if ("V17-COMPAT".equals(r.path("id").asText(""))) {
                ((ObjectNode) r.path("gates").path(gate)).put("status", status);
                return m;
            }
        }
        throw new AssertionError("V17-COMPAT not found");
    }

    private int run(Path result, Path doc, Path manifest) {
        java.util.List<String> args = new java.util.ArrayList<>();
        args.add(result.toString());
        if (doc != null) {
            args.add("--doc"); args.add(doc.toString());
        }
        if (manifest != null) {
            args.add("--manifest"); args.add(manifest.toString());
        }
        return CompatibilityVerifierMain.runInProcess(args.toArray(new String[0]));
    }

    @Test
    void passingResultWithMatchingDocAndCommittedManifestExits0() throws Exception {
        ObjectNode result = aggregate(FX.passingMatrix());
        Path r = writeResult(result);
        Path d = writeDoc(result);
        Path m = CompatibilityRepoPaths.acceptanceManifest();
        assertThat(run(r, d, m)).isZero();
    }

    @Test
    void singleArgFormWithoutCrossCheckStillExits0() throws Exception {
        // Backward compatibility: the M3-A single-arg form is preserved.
        ObjectNode result = aggregate(FX.passingMatrix());
        assertThat(run(writeResult(result), null, null)).isZero();
    }

    @Test
    void tamperedDocExits4() throws Exception {
        ObjectNode result = aggregate(FX.passingMatrix());
        Path r = writeResult(result);
        Path d = tmp.resolve("v1.7.md");
        Files.writeString(d, CompatibilityDocumentGenerator.generate(result)
                .replace("- Overall: `PASSED`", "- Overall: `FAILED`"));
        Path m = CompatibilityRepoPaths.acceptanceManifest();
        assertThat(run(r, d, m)).isEqualTo(4);
    }

    @Test
    void manifestRcPassedExits4() throws Exception {
        ObjectNode result = aggregate(FX.passingMatrix());
        Path r = writeResult(result);
        Path d = writeDoc(result);
        Path m = writeManifest(setGate(committedManifest(), "RC", "PASSED"));
        assertThat(run(r, d, m)).isEqualTo(4);
    }

    @Test
    void manifestPrPassedButMatrixFailedExits4() throws Exception {
        // NOT_RUN matrix + a manifest that overclaims PR PASSED: the aggregate is FAILED
        // (exit 4) AND the manifest overclaims (also exit 4). Either way fail-closed.
        ObjectNode result = aggregate(FX.m3aNotRunMatrix());
        Path r = writeResult(result);
        Path d = writeDoc(result);
        Path m = writeManifest(setGate(committedManifest(), "PR", "PASSED"));
        assertThat(run(r, d, m)).isEqualTo(4);
    }

    @Test
    void notRunResultExits4EvenWithCleanCrossCheck() throws Exception {
        // The NOT_RUN matrix is structurally valid and the doc/manifest cross-check is clean,
        // but overall is FAILED -> exit 4 (the matrix has not passed).
        ObjectNode result = aggregate(FX.m3aNotRunMatrix());
        Path r = writeResult(result);
        Path d = writeDoc(result);
        Path m = CompatibilityRepoPaths.acceptanceManifest();
        assertThat(run(r, d, m)).isEqualTo(4);
    }

    @Test
    void missingDocFileExits4() throws Exception {
        ObjectNode result = aggregate(FX.passingMatrix());
        Path r = writeResult(result);
        Path missing = tmp.resolve("nope.md");
        Path m = CompatibilityRepoPaths.acceptanceManifest();
        assertThat(run(r, missing, m)).isEqualTo(4);
    }
}
