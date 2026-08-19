package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
 * readiness. {@link #checkManifest(JsonNode, JsonNode)} verifies the acceptance manifest's
 * gate lifecycle and requires every {@code PASSED} gate to carry complete, attributable
 * evidence that is supported by the aggregate.
 *
 * <p>The check never infers support from job names or duplicates the catalog: support is
 * read from the aggregate, while the manifest supplies release-gate status and provenance.
 * Pure and deterministic: returns a list of error strings (empty = consistent).
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
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Set<String> GATE_STATUSES = Set.of(
            "NOT_RUN", "PASSED", "FAILED", "SKIPPED", "EXPERIMENTAL");

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
     * Validates the acceptance manifest's {@code V17-COMPAT} lifecycle against the
     * aggregate. A passed gate must be supported by a passing aggregate and complete
     * evidence; lifecycle promotion is ordered PR -&gt; RC -&gt; RELEASE.
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
        String pr = gateStatus(gates, "PR");
        String rc = gateStatus(gates, "RC");
        String release = gateStatus(gates, "RELEASE");

        validateGate(result, manifest, gates, "PR", errors);
        validateGate(result, manifest, gates, "RC", errors);
        validateGate(result, manifest, gates, "RELEASE", errors);

        if ("PASSED".equals(rc) && !"PASSED".equals(pr)) {
            errors.add("V17-COMPAT.RC is PASSED but V17-COMPAT.PR is " + display(pr)
                    + " (gate promotion must follow PR -> RC -> RELEASE)");
        }
        if ("PASSED".equals(release) && !"PASSED".equals(rc)) {
            errors.add("V17-COMPAT.RELEASE is PASSED but V17-COMPAT.RC is " + display(rc)
                    + " (gate promotion must follow PR -> RC -> RELEASE)");
        }
        return errors;
    }

    private static void validateGate(JsonNode result, JsonNode manifest, JsonNode gates,
                                     String name, List<String> errors) {
        JsonNode gate = gates.path(name);
        String status = gateStatus(gates, name);
        String prefix = "V17-COMPAT." + name;
        if (!GATE_STATUSES.contains(status)) {
            errors.add(prefix + " has invalid or missing status " + display(status));
            return;
        }
        if (!"PASSED".equals(status)) {
            return;
        }

        String overall = text(result, "overall");
        if (!"PASSED".equals(overall)) {
            errors.add(prefix + " is PASSED but the aggregate overall is '" + overall
                    + "' (the manifest must not overclaim support the matrix does not have)");
        }

        String buildId = text(gate, "buildId");
        if (!COMMIT_SHA.matcher(buildId).matches()) {
            errors.add(prefix + " is PASSED but buildId is not a lowercase 40-character commit SHA");
        }
        if (!gate.path("environment").isObject() || gate.path("environment").isEmpty()) {
            errors.add(prefix + " is PASSED but environment evidence is missing or empty");
        }
        if (!hasNonBlankText(gate.path("commands"))) {
            errors.add(prefix + " is PASSED but commands evidence is missing or empty");
        }
        if (!hasNonBlankText(gate.path("reports"))) {
            errors.add(prefix + " is PASSED but reports evidence is missing or empty");
        } else if (!hasCompatibilityResult(gate.path("reports"))) {
            errors.add(prefix + " is PASSED but reports do not include compatibility-result.json");
        }

        Instant started = parseInstant(gate.path("startedAt"), prefix + ".startedAt", errors);
        Instant ended = parseInstant(gate.path("endedAt"), prefix + ".endedAt", errors);
        if (started != null && ended != null && ended.isBefore(started)) {
            errors.add(prefix + " is PASSED but endedAt is before startedAt");
        }

        // PR can remain a historical fact from an earlier commit. RC and RELEASE certify
        // the frozen manifest candidate and therefore must bind to its top-level buildId.
        if (("RC".equals(name) || "RELEASE".equals(name))
                && COMMIT_SHA.matcher(buildId).matches()
                && !buildId.equals(text(manifest, "buildId"))) {
            errors.add(prefix + " buildId '" + buildId
                    + "' does not match manifest buildId '" + text(manifest, "buildId") + "'");
        }
    }

    private static boolean hasNonBlankText(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return false;
        }
        for (JsonNode value : array) {
            if (value.isTextual() && !value.asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCompatibilityResult(JsonNode reports) {
        for (JsonNode report : reports) {
            if (report.isTextual()
                    && (report.asText().equals("compatibility-result.json")
                    || report.asText().endsWith("/compatibility-result.json"))) {
                return true;
            }
        }
        return false;
    }

    private static Instant parseInstant(JsonNode value, String field, List<String> errors) {
        if (!value.isTextual() || value.asText().isBlank()) {
            errors.add(field + " is required when the gate is PASSED");
            return null;
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException e) {
            errors.add(field + " must be an ISO-8601 instant (got: '" + value.asText() + "')");
            return null;
        }
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "<missing>" : "'" + value + "'";
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
