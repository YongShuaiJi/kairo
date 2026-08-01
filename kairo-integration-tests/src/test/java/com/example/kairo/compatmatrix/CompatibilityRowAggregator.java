package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Pure aggregator for V1.7 compatibility row evidence (section 10.3 / 10.4.1).
 *
 * <p>Consumes <strong>row JSON only</strong>; it never executes scenarios and never
 * reads the workflow or acceptance manifest. It rejects: unparseable files,
 * malformed rows, missing/duplicate/unknown scenarios, build-id mismatch, catalog
 * mismatch, fake PASSED evidence (delegated to {@link CompatibilityRowValidator}),
 * any formal row that is not PASSED, and a C09 row that is not PASSED or
 * EXPERIMENTAL (section 10.5). It produces the <strong>only</strong>
 * {@code compatibility-result.json} schema.
 *
 * <p>The result {@code rows} array preserves the <strong>complete validated row
 * evidence</strong> (deep copy), not a flattened summary, so the verifier can re-run
 * {@link CompatibilityRowValidator} on every row and independently reject tampering.
 *
 * <p>Pure and deterministic: given the same parsed rows and metadata it always
 * produces the same result, so it is unit-testable without a JVM or filesystem.
 */
public final class CompatibilityRowAggregator {

    private static final Pattern HEX40 = Pattern.compile("^[0-9a-f]{40}$");

    /** A parsed row file: the source file name, the parsed JSON (null if unparseable), and any parse error. */
    public record ParsedRow(String fileName, JsonNode json, String parseError) {
        public ParsedRow {
            Objects.requireNonNull(fileName, "fileName");
        }
    }

    /** Aggregator metadata recorded in the result provenance. */
    public record AggregatorMeta(String generatedAt, String command) {
        public AggregatorMeta {
            Objects.requireNonNull(generatedAt, "generatedAt");
            Objects.requireNonNull(command, "command");
        }
    }

    /** The aggregation outcome: the result document, whether it passed, and the failure reasons. */
    public record AggregationOutcome(ObjectNode result, boolean overallPassed, List<String> failureReasons) {
    }

    private final ObjectMapper mapper;

    public CompatibilityRowAggregator(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public AggregationOutcome aggregate(List<ParsedRow> rows, AggregatorMeta meta) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(meta, "meta");
        List<String> failures = new ArrayList<>();
        // scenarioId -> row json, for valid rows only (first occurrence).
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        // scenarioId -> source file name, for duplicate attribution.
        Map<String, String> sourceByScenario = new LinkedHashMap<>();
        // Deep copies of every valid consumed row (incl. duplicates), preserved verbatim in
        // the result rows array so the verifier can re-run the row validator on each.
        List<JsonNode> resultRows = new ArrayList<>();

        for (ParsedRow pr : rows) {
            if (pr.parseError() != null || pr.json() == null) {
                String reason = pr.parseError() != null ? pr.parseError() : "unparseable JSON";
                failures.add("file " + pr.fileName() + ": " + reason);
                // Malformed/unparseable files are recorded only in failures; they do not
                // produce a row in the result rows array (no valid scenario/status).
                continue;
            }
            JsonNode row = pr.json();
            List<String> rowErrors = new CompatibilityRowValidator().validate(row);
            String scenarioId = row.path("scenario").isTextual() ? row.get("scenario").asText() : null;
            if (!rowErrors.isEmpty()) {
                String reason = "malformed row: " + String.join("; ", rowErrors);
                failures.add((scenarioId != null ? scenarioId : "file " + pr.fileName()) + ": " + reason);
                // Malformed rows are recorded only in failures; the scenario is treated as
                // missing from the result rows array.
                continue;
            }
            // Valid row. Check for unknown / duplicate.
            assert scenarioId != null; // validated
            if (!CompatibilityScenarioCatalog.isKnownScenario(scenarioId)) {
                failures.add("unknown scenario: " + scenarioId + " (file " + pr.fileName() + ")");
            }
            if (byId.containsKey(scenarioId)) {
                failures.add("duplicate row for scenario " + scenarioId
                        + " (file " + pr.fileName() + " duplicates " + sourceByScenario.get(scenarioId) + ")");
            } else {
                byId.put(scenarioId, row);
                sourceByScenario.put(scenarioId, pr.fileName());
            }
            // Preserve the complete validated row evidence (deep copy) in the result.
            resultRows.add(row.deepCopy());
        }

        // Missing scenarios.
        for (CompatibilityScenario s : CompatibilityScenarioCatalog.all()) {
            if (!byId.containsKey(s.id())) {
                failures.add("missing row for scenario " + s.id()
                        + " (" + s.supportLevel() + ", " + s.workPackage() + ")");
            }
        }

        // Single candidate build id.
        String candidateBuildId = resolveCandidateBuildId(byId, failures);

        // Formal rows must be PASSED.
        for (CompatibilityScenario s : CompatibilityScenarioCatalog.formalScenarios()) {
            JsonNode row = byId.get(s.id());
            if (row == null) {
                continue; // already recorded as missing
            }
            String status = row.get("status").asText();
            if (!"PASSED".equals(status)) {
                failures.add("formal scenario " + s.id() + " is " + status
                        + " (must be PASSED): " + row.path("failureReason").asText(""));
            }
        }

        // C09 (experimental) must be PASSED or EXPERIMENTAL (section 10.5). Any other
        // status means the experimental row did not produce acceptable evidence and the
        // matrix is not complete.
        JsonNode c09 = byId.get("C09");
        if (c09 != null) {
            String status = c09.get("status").asText();
            if (!"PASSED".equals(status) && !"EXPERIMENTAL".equals(status)) {
                failures.add("experimental scenario C09 is " + status
                        + " (must be PASSED or EXPERIMENTAL per section 10.5)");
            }
        }

        boolean overallPassed = failures.isEmpty();
        ObjectNode result = buildResult(meta, byId, resultRows, candidateBuildId, overallPassed, failures);
        return new AggregationOutcome(result, overallPassed, List.copyOf(failures));
    }

    private String resolveCandidateBuildId(Map<String, JsonNode> byId, List<String> failures) {
        String candidate = null;
        for (JsonNode row : byId.values()) {
            String bid = row.get("buildId").asText();
            if (bid == null || !HEX40.matcher(bid).matches()) {
                failures.add("row " + row.get("scenario").asText() + " has an invalid buildId: " + bid);
                continue;
            }
            if (candidate == null) {
                candidate = bid;
            } else if (!candidate.equals(bid)) {
                failures.add("buildId mismatch: row " + row.get("scenario").asText()
                        + " buildId " + bid + " != " + candidate);
            }
        }
        return candidate;
    }

    private ObjectNode buildResult(AggregatorMeta meta, Map<String, JsonNode> byId,
                                   List<JsonNode> resultRows, String buildId,
                                   boolean overallPassed, List<String> failures) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", CompatibilityScenarioCatalog.SCHEMA_VERSION);
        root.put("catalogVersion", CompatibilityScenarioCatalog.CATALOG_VERSION);
        root.put("generatedAt", meta.generatedAt());
        if (buildId != null) {
            root.put("buildId", buildId);
        } else {
            root.putNull("buildId");
        }
        root.put("command", meta.command());

        // The result rows array carries the complete validated row evidence (deep copy),
        // so the verifier can re-run CompatibilityRowValidator on every row.
        ArrayNode rowsArr = root.putArray("rows");
        int passed = 0, failed = 0, skipped = 0, notRun = 0, experimentalStatus = 0;
        for (JsonNode row : resultRows) {
            rowsArr.add(row);
            String status = row.path("status").isTextual() ? row.get("status").asText() : "?";
            switch (status) {
                case "PASSED" -> passed++;
                case "FAILED" -> failed++;
                case "SKIPPED" -> skipped++;
                case "NOT_RUN" -> notRun++;
                case "EXPERIMENTAL" -> experimentalStatus++;
                default -> { /* unknown status counted only as a row, not a status bucket */ }
            }
        }

        ObjectNode summary = root.putObject("summary");
        summary.put("total", CompatibilityScenarioCatalog.all().size());
        summary.put("formalScenarios", CompatibilityScenarioCatalog.formalScenarios().size());
        summary.put("experimentalScenarios", CompatibilityScenarioCatalog.experimentalScenarios().size());
        summary.put("rowsConsumed", resultRows.size());
        summary.put("passed", passed);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        summary.put("notRun", notRun);
        summary.put("experimental", experimentalStatus);

        boolean formalComplete = CompatibilityScenarioCatalog.formalScenarios().stream()
                .allMatch(s -> byId.get(s.id()) != null
                        && "PASSED".equals(byId.get(s.id()).get("status").asText()));
        root.put("formalComplete", formalComplete);
        root.put("overall", overallPassed ? "PASSED" : "FAILED");

        ArrayNode failArr = root.putArray("failures");
        for (String f : failures) {
            failArr.add(f);
        }

        ArrayNode excl = root.putArray("nonFormalExclusions");
        for (CompatibilityScenarioCatalog.NonFormalExclusion e : CompatibilityScenarioCatalog.nonFormalExclusions()) {
            ObjectNode n = excl.addObject();
            n.put("combination", e.combination());
            n.put("status", e.status());
        }
        return root;
    }
}
