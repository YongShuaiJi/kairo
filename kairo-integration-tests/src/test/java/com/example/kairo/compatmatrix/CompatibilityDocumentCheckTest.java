package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3-F divergence-rejection tests (section 10.4.6). The cross-checker binds the
 * aggregate, the generated document and the acceptance manifest so their support
 * conclusions cannot diverge: a document not generated from this aggregate is rejected,
 * a release-overclaiming document is rejected, and a manifest with an invalid lifecycle,
 * incomplete passed-gate evidence, or unsupported support claim is rejected.
 */
class CompatibilityDocumentCheckTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final CompatibilityRowFixtures FX = new CompatibilityRowFixtures(MAPPER);
    private static final CompatibilityRowAggregator.AggregatorMeta META =
            new CompatibilityRowAggregator.AggregatorMeta("2026-08-01T00:00:00Z",
                    "./aggregate-compatibility.sh --input x --output y");

    private ObjectNode passingResult() {
        return aggregate(FX.passingMatrix());
    }

    private ObjectNode notRunResult() {
        return aggregate(FX.m3aNotRunMatrix());
    }

    private ObjectNode aggregate(java.util.List<JsonNode> rows) {
        java.util.List<CompatibilityRowAggregator.ParsedRow> parsed = new java.util.ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            parsed.add(new CompatibilityRowAggregator.ParsedRow("row-" + i + ".json", rows.get(i), null));
        }
        return new CompatibilityRowAggregator(MAPPER).aggregate(parsed, META).result();
    }

    private ObjectNode committedManifest() throws Exception {
        return (ObjectNode) MAPPER.readTree(Files.readString(CompatibilityRepoPaths.acceptanceManifest()));
    }

    private ObjectNode setGate(ObjectNode manifest, String gate, String status) {
        ObjectNode m = manifest.deepCopy();
        gate(m, gate).put("status", status);
        return m;
    }

    private ObjectNode gate(ObjectNode manifest, String gate) {
        for (JsonNode r : manifest.path("requirements")) {
            if ("V17-COMPAT".equals(r.path("id").asText(""))) {
                return (ObjectNode) r.path("gates").path(gate);
            }
        }
        throw new AssertionError("V17-COMPAT not found");
    }

    private CompatibilityDocumentCheck check() {
        return new CompatibilityDocumentCheck();
    }

    // --- positive: document generated from this aggregate is consistent ---

    @Test
    void documentGeneratedFromResultIsConsistent() {
        ObjectNode result = passingResult();
        String doc = CompatibilityDocumentGenerator.generate(result);
        assertThat(check().checkDocument(result, doc)).isEmpty();
    }

    @Test
    void committedPrPassIsRejectedAgainstNotRunAggregate() throws Exception {
        // M3 PR acceptance is now a historical PASSED fact. A NOT_RUN aggregate cannot
        // substantiate that claim and must fail the divergence check.
        assertThat(check().checkManifest(notRunResult(), committedManifest()))
                .anyMatch(e -> e.contains("V17-COMPAT.PR is PASSED") && e.contains("overclaim"));
    }

    @Test
    void committedManifestIsConsistentWithPassingAggregate() throws Exception {
        // The committed historical PR PASSED claim is consistent with a passing aggregate.
        assertThat(check().checkManifest(passingResult(), committedManifest())).isEmpty();
    }

    // --- document divergence (tamper rejection) ---

    @Test
    void documentFromDifferentResultRejected() {
        // A document generated from the NOT_RUN result must not be accepted against a
        // passing result (hash + overall + buildId all differ).
        String doc = CompatibilityDocumentGenerator.generate(notRunResult());
        assertThat(check().checkDocument(passingResult(), doc))
                .anyMatch(e -> e.contains("source hash") && e.contains("does not match"));
    }

    @Test
    void documentMissingProvenanceMarkerRejected() {
        ObjectNode result = passingResult();
        String doc = CompatibilityDocumentGenerator.generate(result)
                .replace(CompatibilityDocumentGenerator.PROVENANCE_MARKER, "<!-- x -->");
        assertThat(check().checkDocument(result, doc)).anyMatch(e -> e.contains("provenance marker"));
    }

    @Test
    void documentReleaseOverclaimRejected() {
        ObjectNode result = passingResult();
        String doc = CompatibilityDocumentGenerator.generate(result) + "\nLTS Certified.\n";
        assertThat(check().checkDocument(result, doc)).anyMatch(e -> e.contains("overclaims"));
    }

    @Test
    void documentTamperedCatalogVersionRejected() {
        ObjectNode result = passingResult();
        String doc = CompatibilityDocumentGenerator.generate(result)
                .replace("- Catalog version: `v1.7-1.0`", "- Catalog version: `v9.9`");
        assertThat(check().checkDocument(result, doc)).anyMatch(e -> e.contains("catalog version"));
    }

    @Test
    void documentTamperedRowRejectedEvenWhenHeaderAndSourceHashRemainUntouched() {
        ObjectNode result = passingResult();
        String doc = CompatibilityDocumentGenerator.generate(result)
                .replace("| C08 | PASSED |", "| C08 | FAILED |");
        assertThat(check().checkDocument(result, doc))
                .anyMatch(e -> e.contains("not byte-identical"));
    }

    @Test
    void documentBlankRejected() {
        assertThat(check().checkDocument(passingResult(), ""))
                .anyMatch(e -> e.contains("null/blank"));
    }

    // --- manifest divergence ---

    @Test
    void manifestRcPassedWithoutBuildIdRejected() throws Exception {
        ObjectNode manifest = committedManifest();
        gate(manifest, "RC").remove("buildId");
        assertThat(check().checkManifest(passingResult(), manifest))
                .anyMatch(e -> e.contains("V17-COMPAT.RC") && e.contains("buildId"));
    }

    @Test
    void manifestReleasePassedWithoutEvidenceRejected() throws Exception {
        ObjectNode manifest = setGate(committedManifest(), "RELEASE", "PASSED");
        assertThat(check().checkManifest(passingResult(), manifest))
                .anyMatch(e -> e.contains("V17-COMPAT.RELEASE") && e.contains("buildId"));
    }

    @Test
    void manifestReleasePassedWithCompleteEvidenceAccepted() throws Exception {
        ObjectNode manifest = committedManifest();
        ObjectNode releaseEvidence = gate(manifest, "RC").deepCopy();
        releaseEvidence.put("status", "PASSED");
        gate(manifest, "RELEASE").removeAll().setAll(releaseEvidence);
        assertThat(check().checkManifest(passingResult(), manifest)).isEmpty();
    }

    @Test
    void manifestReleasePassedBeforeRcRejected() throws Exception {
        ObjectNode manifest = committedManifest();
        ObjectNode releaseEvidence = gate(manifest, "RC").deepCopy();
        releaseEvidence.put("status", "PASSED");
        gate(manifest, "RELEASE").removeAll().setAll(releaseEvidence);
        gate(manifest, "RC").put("status", "NOT_RUN");
        assertThat(check().checkManifest(passingResult(), manifest))
                .anyMatch(e -> e.contains("RELEASE is PASSED") && e.contains("RC"));
    }

    @Test
    void manifestUnknownGateStatusRejected() throws Exception {
        ObjectNode manifest = setGate(committedManifest(), "RELEASE", "CERTIFIED");
        assertThat(check().checkManifest(passingResult(), manifest))
                .anyMatch(e -> e.contains("V17-COMPAT.RELEASE") && e.contains("invalid"));
    }

    @Test
    void manifestPrPassedButAggregateFailedRejected() throws Exception {
        // The manifest may not claim V17-COMPAT.PR PASSED when the aggregate is FAILED.
        ObjectNode manifest = setGate(committedManifest(), "PR", "PASSED");
        assertThat(check().checkManifest(notRunResult(), manifest))
                .anyMatch(e -> e.contains("V17-COMPAT.PR is PASSED") && e.contains("overclaim"));
    }

    @Test
    void manifestPrPassedWithPassingAggregateAccepted() throws Exception {
        ObjectNode manifest = setGate(committedManifest(), "PR", "PASSED");
        assertThat(check().checkManifest(passingResult(), manifest)).isEmpty();
    }

    @Test
    void manifestMissingV17CompatRejected() throws Exception {
        ObjectNode manifest = committedManifest();
        // Jackson ArrayNode has no removeIf; rebuild the requirements without V17-COMPAT.
        com.fasterxml.jackson.databind.node.ArrayNode filtered = MAPPER.createArrayNode();
        for (JsonNode r : manifest.path("requirements")) {
            if (!"V17-COMPAT".equals(r.path("id").asText(""))) {
                filtered.add(r);
            }
        }
        manifest.set("requirements", filtered);
        assertThat(check().checkManifest(passingResult(), manifest))
                .anyMatch(e -> e.contains("missing the V17-COMPAT requirement"));
    }

    // --- committed lifecycle must remain internally consistent as gates are promoted ---

    @Test
    void committedManifestHasValidCompatibilityLifecycle() throws Exception {
        JsonNode manifest = committedManifest();
        JsonNode compat = null;
        for (JsonNode r : manifest.path("requirements")) {
            if ("V17-COMPAT".equals(r.path("id").asText(""))) {
                compat = r;
                break;
            }
        }
        assertThat(compat).as("committed manifest has V17-COMPAT").isNotNull();
        assertThat(check().checkManifest(passingResult(), manifest)).isEmpty();
    }
}
