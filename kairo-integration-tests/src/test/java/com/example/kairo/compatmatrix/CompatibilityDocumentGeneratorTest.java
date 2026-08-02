package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic generator tests for the M3-F compatibility document (section 10.4.6):
 * reproducibility, the committed {@code docs/compatibility/v1.7.md} matches a regeneration
 * from the representative (test-only, NOT_RUN) matrix, and tampering with the document is
 * rejected by {@link CompatibilityDocumentCheck}.
 */
class CompatibilityDocumentGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CompatibilityRowFixtures FX = new CompatibilityRowFixtures(MAPPER);

    /**
     * Fixed metadata for the committed representative document. The committed doc is a
     * repository placeholder generated from the honest M3-A NOT_RUN state (the matrix has
     * not been executed on real Linux CI); it is NOT an actual compatibility run. Both
     * fields are fixed so regeneration is byte-stable and never embeds a wall-clock time.
     */
    static final CompatibilityRowAggregator.AggregatorMeta REPRESENTATIVE_META =
            new CompatibilityRowAggregator.AggregatorMeta("2026-08-01T00:00:00Z",
                    "representative-fixture aggregate-compatibility.sh --input rows --output result.json");

    /** The committed placeholder document path (resolved from the repo root). */
    private static final Path COMMITTED_DOC = CompatibilityRepoPaths.committedDocument();

    private ObjectNode representativeResult() {
        return new CompatibilityRowAggregator(MAPPER)
                .aggregate(wrap(FX.m3aNotRunMatrix()), REPRESENTATIVE_META).result();
    }

    private static java.util.List<CompatibilityRowAggregator.ParsedRow> wrap(java.util.List<JsonNode> rows) {
        java.util.List<CompatibilityRowAggregator.ParsedRow> out = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            out.add(new CompatibilityRowAggregator.ParsedRow("row-" + i + ".json", rows.get(i), null));
        }
        return out;
    }

    @Test
    void regenerationIsDeterministic() {
        ObjectNode result = representativeResult();
        String doc1 = CompatibilityDocumentGenerator.generate(result);
        String doc2 = CompatibilityDocumentGenerator.generate(result);
        assertThat(doc1).isEqualTo(doc2);
    }

    @Test
    void committedDocMatchesRegenerationFromRepresentativeMatrix() throws Exception {
        // The committed docs/compatibility/v1.7.md MUST be byte-identical to a regeneration
        // from the representative matrix. If this fails, the committed doc was hand-edited,
        // the generator changed, or the representative input drifted - regenerate it.
        assertThat(Files.isRegularFile(COMMITTED_DOC))
                .as("committed docs/compatibility/v1.7.md exists")
                .isTrue();
        String committed = Files.readString(COMMITTED_DOC);
        String regenerated = CompatibilityDocumentGenerator.generate(representativeResult());
        assertThat(committed).isEqualTo(regenerated);
    }

    @Test
    void committedDocCrossChecksCleanAgainstRepresentativeResult() {
        // The committed document must be consistent with the representative aggregate: the
        // document check (hash/overall/buildId/provenance/no-overclaim) returns no errors.
        ObjectNode result = representativeResult();
        String doc = CompatibilityDocumentGenerator.generate(result);
        assertThat(new CompatibilityDocumentCheck().checkDocument(result, doc)).isEmpty();
    }

    @Test
    void representativeResultIsStructurallyValidAggregate() {
        // The committed doc's source is a structurally valid aggregate (it is NOT an actual
        // run, but it has the right shape so the verifier accepts it structurally).
        ObjectNode result = representativeResult();
        assertThat(new CompatibilityResultValidator().validateStructure(result)).isEmpty();
        assertThat(result.get("overall").asText()).isEqualTo("FAILED");
    }

    // --- tamper rejection ---

    @Test
    void tamperedOverallRejected() {
        ObjectNode result = representativeResult();
        String doc = CompatibilityDocumentGenerator.generate(result);
        // Re-serialize a tampered doc: flip the overall line to PASSED while the result is FAILED.
        String tampered = doc.replace("- Overall: `FAILED`", "- Overall: `PASSED`");
        assertThat(new CompatibilityDocumentCheck().checkDocument(result, tampered))
                .anyMatch(e -> e.contains("overall") && e.contains("does not match"));
    }

    @Test
    void tamperedBuildIdRejected() {
        ObjectNode result = representativeResult();
        String doc = CompatibilityDocumentGenerator.generate(result);
        String tampered = doc.replace(
                "- Build id: `" + result.get("buildId").asText() + "`",
                "- Build id: `0000000000000000000000000000000000000000`");
        assertThat(new CompatibilityDocumentCheck().checkDocument(result, tampered))
                .anyMatch(e -> e.contains("build id") && e.contains("does not match"));
    }

    @Test
    void tamperedSourceHashRejected() {
        ObjectNode result = representativeResult();
        String doc = CompatibilityDocumentGenerator.generate(result);
        String tampered = doc.replaceAll("- Source hash \\(SHA-256\\): `[0-9a-fA-F]{64}`",
                "- Source hash (SHA-256): `deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef`");
        assertThat(new CompatibilityDocumentCheck().checkDocument(result, tampered))
                .anyMatch(e -> e.contains("source hash") && e.contains("does not match"));
    }

    @Test
    void missingProvenanceMarkerRejected() {
        ObjectNode result = representativeResult();
        String doc = CompatibilityDocumentGenerator.generate(result)
                .replace(CompatibilityDocumentGenerator.PROVENANCE_MARKER, "<!-- removed -->");
        assertThat(new CompatibilityDocumentCheck().checkDocument(result, doc))
                .anyMatch(e -> e.contains("provenance marker"));
    }

    @Test
    void releaseOverclaimRejected() {
        ObjectNode result = representativeResult();
        String doc = CompatibilityDocumentGenerator.generate(result)
                + "\nThis matrix is LTS Certified and release certified.\n";
        assertThat(new CompatibilityDocumentCheck().checkDocument(result, doc))
                .anyMatch(e -> e.contains("overclaims release readiness"));
    }

    @Test
    void documentIdentifiesBuildIdStatusSupportAndEvidence() {
        ObjectNode result = representativeResult();
        String doc = CompatibilityDocumentGenerator.generate(result);
        // Build id, overall, source hash embedded.
        assertThat(doc).contains("- Build id: `" + result.get("buildId").asText() + "`");
        assertThat(doc).contains("- Overall: `FAILED`");
        assertThat(doc).contains("- Source hash (SHA-256):");
        // Every scenario id, status, support level and fixture present.
        for (CompatibilityScenario s : CompatibilityScenarioCatalog.all()) {
            JsonNode row = findRow(result, s.id());
            String status = row.get("status").asText();
            assertThat(doc).contains("| " + s.id() + " | " + status + " | "
                    + s.supportLevel().name() + " | " + s.fixture());
        }
        // Non-formal exclusions (limitations) present.
        assertThat(doc).contains("Non-formal exclusions");
        assertThat(doc).contains("JDK 8/11");
    }

    private static JsonNode findRow(JsonNode result, String scenarioId) {
        for (JsonNode r : result.path("rows")) {
            if (r.get("scenario").asText().equals(scenarioId)) {
                return r;
            }
        }
        throw new AssertionError("row not found: " + scenarioId);
    }
}
