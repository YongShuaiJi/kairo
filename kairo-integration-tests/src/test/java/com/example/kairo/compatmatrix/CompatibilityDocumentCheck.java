package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure cross-checker that binds {@code compatibility-result.json}, the generated
 * {@code docs/compatibility/v1.7.md} and {@code v1.7-acceptance-manifest.json} together
 * (section 10.4.6), so their support conclusions cannot diverge.
 *
 * <p>{@link #checkDocument(JsonNode, String)} verifies the document was generated from the
 * supplied aggregate (the embedded SHA-256 source hash, overall, build id and catalog
 * version must match), carries the provenance marker, and does not overclaim release
 * readiness. {@link #checkManifest(JsonNode, JsonNode)} verifies the acceptance manifest
 * keeps {@code V17-COMPAT.RC}/{@code RELEASE} at {@code NOT_RUN} (no release-candidate
 * commit has been executed) and never claims a gate {@code PASSED} the aggregate does not
 * support.
 *
 * <p>The check never infers support from job names or duplicates the catalog: support is
 * read from the aggregate, and the manifest supplies only the release-gate invariant. Pure
 * and deterministic: returns a list of error strings (empty = consistent).
 */
public final class CompatibilityDocumentCheck {

    /** Phrases a generated document must never contain (release overclaim). */
    private static final List<String> RELEASE_OVERCLAIM_PHRASES = List.of(
            "RC PASSED", "RELEASE PASSED", "release certified", "LTS Certified");

    private static final Pattern BUILD_ID =
            Pattern.compile("- Build id: `([^`]*)`");
    private static final Pattern OVERALL =
            Pattern.compile("- Overall: `([^`]*)`");
    private static final Pattern CATALOG_VERSION =
            Pattern.compile("- Catalog version: `([^`]*)`");
    private static final Pattern SOURCE_HASH =
            Pattern.compile("- Source hash \\(SHA-256\\): `([0-9a-fA-F]{64})`");

    /** Validates the generated document against its source aggregate. */
    public List<String> checkDocument(JsonNode result, String document) {
        List<String> errors = new ArrayList<>();
        if (document == null || document.isBlank()) {
            errors.add("document is null/blank");
            return errors;
        }
        String expectedDocument;
        try {
            expectedDocument = CompatibilityDocumentGenerator.generate(result);
        } catch (RuntimeException e) {
            errors.add("expected document could not be generated from the aggregate: "
                    + e.getMessage());
            return errors;
        }
        if (!document.equals(expectedDocument)) {
            errors.add("document is not byte-identical to the document generated from "
                    + "the supplied aggregate");
        }
        if (!document.contains(CompatibilityDocumentGenerator.PROVENANCE_MARKER)) {
            errors.add("document is missing the generator provenance marker");
        }
        String docBuildId = extract(document, BUILD_ID);
        String docOverall = extract(document, OVERALL);
        String docCatalog = extract(document, CATALOG_VERSION);
        String docHash = extract(document, SOURCE_HASH);

        String resultCatalog = text(result, "catalogVersion");
        if (docCatalog == null) {
            errors.add("document does not embed a catalog version");
        } else if (!docCatalog.equals(resultCatalog)) {
            errors.add("document catalog version '" + docCatalog
                    + "' does not match result '" + resultCatalog + "'");
        }
        String resultOverall = text(result, "overall");
        if (docOverall == null) {
            errors.add("document does not embed an overall status");
        } else if (!docOverall.equals(resultOverall)) {
            errors.add("document overall '" + docOverall
                    + "' does not match result overall '" + resultOverall + "'");
        }
        String resultBuildId = result.path("buildId").isTextual()
                ? result.get("buildId").asText() : "(none)";
        if (docBuildId == null) {
            errors.add("document does not embed a build id");
        } else if (!docBuildId.equals(resultBuildId)) {
            errors.add("document build id '" + docBuildId
                    + "' does not match result build id '" + resultBuildId + "'");
        }
        if (docHash == null) {
            errors.add("document does not embed a source hash");
        } else {
            String expected = CompatibilityDocumentGenerator.sourceHash(result);
            if (!docHash.equalsIgnoreCase(expected)) {
                errors.add("document source hash '" + docHash
                        + "' does not match the canonical result hash '" + expected + "'");
            }
        }
        for (String phrase : RELEASE_OVERCLAIM_PHRASES) {
            if (document.contains(phrase)) {
                errors.add("document overclaims release readiness (contains '" + phrase + "')");
            }
        }
        return errors;
    }

    /**
     * Validates the acceptance manifest's {@code V17-COMPAT} gate conclusions against the
     * aggregate: RC and RELEASE must remain {@code NOT_RUN}, and no gate may claim
     * {@code PASSED} the aggregate does not support.
     */
    public List<String> checkManifest(JsonNode result, JsonNode manifest) {
        List<String> errors = new ArrayList<>();
        if (manifest == null || manifest.isMissingNode() || manifest.isNull()) {
            errors.add("manifest is null/missing");
            return errors;
        }
        JsonNode reqs = manifest.path("requirements");
        JsonNode compat = null;
        if (reqs.isArray()) {
            for (JsonNode r : reqs) {
                if ("V17-COMPAT".equals(r.path("id").asText(""))) {
                    compat = r;
                    break;
                }
            }
        }
        if (compat == null) {
            errors.add("manifest is missing the V17-COMPAT requirement");
            return errors;
        }
        JsonNode gates = compat.path("gates");
        String rc = gateStatus(gates, "RC");
        String release = gateStatus(gates, "RELEASE");
        if (!"NOT_RUN".equals(rc)) {
            errors.add("V17-COMPAT.RC must remain NOT_RUN until a final release-candidate "
                    + "commit is actually executed (got: " + rc + ")");
        }
        if (!"NOT_RUN".equals(release)) {
            errors.add("V17-COMPAT.RELEASE must remain NOT_RUN (got: " + release + ")");
        }
        String pr = gateStatus(gates, "PR");
        if ("PASSED".equals(pr)) {
            String overall = text(result, "overall");
            if (!"PASSED".equals(overall)) {
                errors.add("V17-COMPAT.PR is PASSED but the aggregate overall is '" + overall
                        + "' (the manifest must not overclaim support the matrix does not have)");
            }
        }
        return errors;
    }

    private static String gateStatus(JsonNode gates, String name) {
        JsonNode g = gates.path(name);
        JsonNode s = g.path("status");
        return s.isTextual() ? s.asText() : "";
    }

    private static String extract(String document, Pattern p) {
        Matcher m = p.matcher(document);
        return m.find() ? m.group(1) : null;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode n = parent == null ? null : parent.path(field);
        return (n != null && n.isTextual()) ? n.asText() : "";
    }
}
