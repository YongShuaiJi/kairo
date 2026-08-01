package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validator for {@code compatibility-result.json} (the aggregate produced by
 * {@link CompatibilityRowAggregator}). This is the core of
 * {@code verify-compatibility.sh} (section 10.3 / 10.4.1).
 *
 * <p>It validates the aggregate schema, then <strong>re-runs
 * {@link CompatibilityRowValidator} on every row</strong> in the result (the rows array
 * carries the complete validated row evidence, not a flattened summary) so it
 * independently rejects tampering of command, catalog, loadingMode, fixture,
 * timestamps, target JVM/JDK, assertions, mode/dirty state and PASSED provenance -
 * it does not rely only on the aggregator having validated its input. It also checks
 * catalog completeness (all C01-C10 present exactly once), a single candidate build
 * id, formal-row status semantics, C09 completion (PASSED or EXPERIMENTAL per
 * section 10.5), summary/count consistency, exact non-formal exclusions and an
 * ISO-8601 generatedAt. It <strong>does not rerun scenarios</strong> and never reads
 * {@code docs/compatibility/v1.7.md} or the release manifest (that cross-check is
 * M3-F, section 10.4.6).
 *
 * <p>Pure and deterministic: returns a list of error strings (empty = valid).
 */
public final class CompatibilityResultValidator {

    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("<[^>]*>|\\.\\.\\.");
    private static final Set<String> STATUSES = CompatibilityRowValidator.STATUSES;
    private static final CompatibilityRowValidator ROW_VALIDATOR = new CompatibilityRowValidator();

    public List<String> validate(JsonNode root) {
        return validate(root, true);
    }

    /**
     * Structural-only validation used by the aggregator to self-check that it produced a
     * well-formed document. It re-validates each row but does NOT require catalog
     * completeness, a single candidate build id, duplicate-free rows, or C09 completion -
     * those are honest FAILED content (e.g. the M3-A NOT_RUN / empty state) or
     * gate-level completion checks, not an aggregator malfunction. The verifier uses the
     * full {@link #validate(JsonNode)} (gate=true) instead.
     */
    public List<String> validateStructure(JsonNode root) {
        return validate(root, false);
    }

    private List<String> validate(JsonNode root, boolean gate) {
        List<String> errors = new ArrayList<>();
        if (root == null || root.isMissingNode() || root.isNull()) {
            errors.add("result is null/missing");
            return errors;
        }
        requireText(errors, root, "schemaVersion", CompatibilityScenarioCatalog.SCHEMA_VERSION);
        requireText(errors, root, "catalogVersion", CompatibilityScenarioCatalog.CATALOG_VERSION);
        // generatedAt must be a parseable ISO-8601 instant.
        String generatedAt = textOrNull(root, "generatedAt");
        if (generatedAt == null || generatedAt.isBlank()) {
            errors.add("generatedAt must be a non-blank string");
        } else {
            try {
                Instant.parse(generatedAt);
            } catch (DateTimeParseException e) {
                errors.add("generatedAt must be an ISO-8601 instant (got: " + generatedAt + ")");
            }
        }

        String command = textOrNull(root, "command");
        if (command == null || command.isBlank()) {
            errors.add("missing command");
        } else if (PLACEHOLDER.matcher(command).find()) {
            errors.add("command must not contain <...> or ... placeholders");
        }

        JsonNode rows = root.path("rows");
        if (!rows.isArray()) {
            errors.add("rows must be an array");
            return errors;
        }

        // Re-run the row validator on every aggregate row (independent tamper rejection)
        // and collect scenario ids / build ids / status counts for the aggregate checks.
        Set<String> seenScenarios = new HashSet<>();
        Set<String> buildIds = new HashSet<>();
        int passed = 0, failed = 0, skipped = 0, notRun = 0, experimental = 0;
        for (int i = 0; i < rows.size(); i++) {
            JsonNode r = rows.get(i);
            List<String> rowErrors = ROW_VALIDATOR.validate(r);
            String sid = r.path("scenario").isTextual() ? r.get("scenario").asText() : "?";
            for (String re : rowErrors) {
                errors.add("rows[" + i + "] (" + sid + "): " + re);
            }
            String scenarioId = r.path("scenario").isTextual() ? r.get("scenario").asText() : null;
            if (scenarioId == null || !CompatibilityScenarioCatalog.isKnownScenario(scenarioId)) {
                errors.add("rows[" + i + "].scenario must be a known C01-C10 (got: " + scenarioId + ")");
                continue;
            }
            if (!seenScenarios.add(scenarioId) && gate) {
                errors.add("duplicate row for scenario " + scenarioId);
            }
            String status = r.path("status").isTextual() ? r.get("status").asText() : "?";
            if (!STATUSES.contains(status)) {
                status = "?";
            }
            switch (status) {
                case "PASSED" -> passed++;
                case "FAILED" -> failed++;
                case "SKIPPED" -> skipped++;
                case "NOT_RUN" -> notRun++;
                case "EXPERIMENTAL" -> experimental++;
                default -> { }
            }
            JsonNode bid = r.path("buildId");
            if (bid.isTextual()) {
                buildIds.add(bid.asText());
            }
        }

        // Catalog completeness: every C01-C10 present exactly once (verifier gate).
        if (gate) {
            for (CompatibilityScenario s : CompatibilityScenarioCatalog.all()) {
                if (!seenScenarios.contains(s.id())) {
                    errors.add("missing row for scenario " + s.id());
                }
            }
            // C09 completion (section 10.5): must be PASSED or EXPERIMENTAL.
            JsonNode c09 = findRow(rows, "C09");
            if (c09 != null) {
                String cs = c09.path("status").asText("");
                if (!"PASSED".equals(cs) && !"EXPERIMENTAL".equals(cs)) {
                    errors.add("experimental scenario C09 must be PASSED or EXPERIMENTAL (got: " + cs + ")");
                }
            }
        }

        // Single candidate build id: result.buildId must be present, 40-hex, and match
        // every row (verifier gate; a FAILED aggregate with no rows may legitimately have
        // no build id).
        String resultBuildId = textOrNull(root, "buildId");
        if (gate) {
            if (resultBuildId == null || !HEX40.matcher(resultBuildId).matches()) {
                errors.add("buildId must be a single 40-hex candidate commit id (got: " + resultBuildId + ")");
            } else {
                for (String bid : buildIds) {
                    if (!resultBuildId.equals(bid)) {
                        errors.add("result buildId " + resultBuildId + " does not match row buildId " + bid);
                    }
                }
            }
        }

        // Summary / count consistency.
        validateSummary(errors, root.path("summary"), rows.size(),
                passed, failed, skipped, notRun, experimental);

        // formalComplete must match the actual formal-row statuses (re-derived, not trusted).
        boolean actualFormalComplete = CompatibilityScenarioCatalog.formalScenarios().stream()
                .allMatch(s -> findRow(rows, s.id()) != null
                        && "PASSED".equals(findRow(rows, s.id()).path("status").asText("")));
        JsonNode formalComplete = root.path("formalComplete");
        if (!formalComplete.isBoolean()) {
            errors.add("formalComplete must be boolean");
        } else if (formalComplete.asBoolean() != actualFormalComplete) {
            errors.add("formalComplete must be " + actualFormalComplete
                    + " (got: " + formalComplete.asBoolean() + ") based on formal-row statuses");
        }

        String overall = textOrNull(root, "overall");
        if (!"PASSED".equals(overall) && !"FAILED".equals(overall)) {
            errors.add("overall must be PASSED or FAILED (got: " + overall + ")");
        }
        JsonNode failures = root.path("failures");
        if (!failures.isArray()) {
            errors.add("failures must be an array");
        } else if ("PASSED".equals(overall) && !failures.isEmpty()) {
            errors.add("overall is PASSED but failures is non-empty");
        } else if ("FAILED".equals(overall) && failures.isEmpty()) {
            errors.add("overall is FAILED but failures is empty");
        }

        // nonFormalExclusions must match the section 10.2 catalog exactly (entries + statuses).
        validateNonFormalExclusions(errors, root.path("nonFormalExclusions"));

        // Formal-row status semantics: a PASSED aggregate must have every formal row
        // PASSED (a FAILED aggregate is still structurally valid - it is the honest
        // report of a not-yet-passing matrix, e.g. the M3-A NOT_RUN state).
        if ("PASSED".equals(overall)) {
            if (!actualFormalComplete) {
                errors.add("overall is PASSED but not all formal scenarios are PASSED");
            }
            if (formalComplete.isBoolean() && !formalComplete.asBoolean()) {
                errors.add("overall is PASSED but formalComplete is false");
            }
            if (resultBuildId == null || !HEX40.matcher(resultBuildId).matches()) {
                errors.add("overall is PASSED but buildId is not a valid candidate commit");
            }
            JsonNode c09 = findRow(rows, "C09");
            if (c09 != null) {
                String cs = c09.path("status").asText("");
                if (!"PASSED".equals(cs) && !"EXPERIMENTAL".equals(cs)) {
                    errors.add("overall is PASSED but C09 is " + cs + " (must be PASSED or EXPERIMENTAL)");
                }
            }
        }
        return errors;
    }

    private void validateNonFormalExclusions(List<String> errors, JsonNode excl) {
        List<CompatibilityScenarioCatalog.NonFormalExclusion> expected =
                CompatibilityScenarioCatalog.nonFormalExclusions();
        if (!excl.isArray() || excl.size() != expected.size()) {
            errors.add("nonFormalExclusions must list the section 10.2 exclusions exactly ("
                    + expected.size() + " entries)");
            return;
        }
        for (int i = 0; i < expected.size(); i++) {
            JsonNode n = excl.get(i);
            if (!n.isObject()) {
                errors.add("nonFormalExclusions[" + i + "] must be an object");
                continue;
            }
            String comb = n.path("combination").isTextual() ? n.get("combination").asText() : null;
            String stat = n.path("status").isTextual() ? n.get("status").asText() : null;
            if (!expected.get(i).combination().equals(comb) || !expected.get(i).status().equals(stat)) {
                errors.add("nonFormalExclusions[" + i + "] must be {combination='"
                        + expected.get(i).combination() + "', status='" + expected.get(i).status()
                        + "'} (got combination='" + comb + "', status='" + stat + "')");
            }
        }
    }

    private void validateSummary(List<String> errors, JsonNode s, int rowsSize,
                                 int passed, int failed, int skipped, int notRun, int experimental) {
        if (!s.isObject()) {
            errors.add("missing summary object");
            return;
        }
        checkInt(errors, s, "total", CompatibilityScenarioCatalog.all().size());
        checkInt(errors, s, "formalScenarios", CompatibilityScenarioCatalog.formalScenarios().size());
        checkInt(errors, s, "experimentalScenarios", CompatibilityScenarioCatalog.experimentalScenarios().size());
        checkInt(errors, s, "rowsConsumed", rowsSize);
        checkInt(errors, s, "passed", passed);
        checkInt(errors, s, "failed", failed);
        checkInt(errors, s, "skipped", skipped);
        checkInt(errors, s, "notRun", notRun);
        checkInt(errors, s, "experimental", experimental);
        int statusSum = passed + failed + skipped + notRun + experimental;
        if (statusSum != rowsSize) {
            errors.add("summary status buckets (" + statusSum
                    + ") must equal rowsConsumed (" + rowsSize + ")");
        }
    }

    private static JsonNode findRow(JsonNode rows, String scenarioId) {
        for (JsonNode r : rows) {
            if (r.path("scenario").isTextual() && r.get("scenario").asText().equals(scenarioId)) {
                return r;
            }
        }
        return null;
    }

    // -------------------------------------------------------- helpers

    private static String textOrNull(JsonNode parent, String field) {
        JsonNode n = parent.path(field);
        return n.isTextual() ? n.asText() : null;
    }

    private static void requireText(List<String> errors, JsonNode parent, String field, String expected) {
        JsonNode n = parent.path(field);
        if (!n.isTextual() || !expected.equals(n.asText())) {
            errors.add(field + " must equal '" + expected + "' (got: "
                    + (n.isTextual() ? n.asText() : "missing") + ")");
        }
    }

    private static void checkInt(List<String> errors, JsonNode parent, String field, int expected) {
        JsonNode n = parent.path(field);
        if (!n.isInt() || n.asInt() != expected) {
            errors.add("summary." + field + " must equal " + expected
                    + " (got: " + (n.isInt() ? n.asInt() : "missing") + ")");
        }
    }
}
