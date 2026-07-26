package com.example.kairo.perf;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The parsed performance budget. The on-disk file
 * {@code v1.7-performance-budget.json} is the single source of truth; this record
 * is its in-memory projection. The runner reads it but <em>never</em> writes or
 * relaxes it — threshold changes must go through documented review.
 *
 * @param schemaVersion   fixed {@code "1.0"} — the reporter writes this verbatim and the
 *                        validator checks it exactly; bumping requires a documented review
 * @param direction       fixed {@code "regression-vs-baseline"} — candidate compared to baseline
 * @param units           fixed {@code "ns-per-op"} — nanoseconds per operation
 * @param metricDirection fixed {@code "lower-is-better"} — a regression is candidate &gt; baseline
 * @param defaultMedianPct max allowed median regression, percent (e.g. 20)
 * @param defaultP95Pct    max allowed P95 regression, percent (e.g. 20)
 * @param scenarioIds      the scenarios the budget applies to (must match the catalog)
 * @param gatedIds         the subset of {@link #scenarioIds()} that GATES the budget
 *                         (key hit/miss paths); the rest are observed-only
 */
public record Budget(
        String schemaVersion,
        String direction,
        String units,
        String metricDirection,
        double defaultMedianPct,
        double defaultP95Pct,
        List<String> scenarioIds,
        List<String> gatedIds) {

    private static final String EXPECTED_SCHEMA = "1.0";

    /** Parse a budget JSON node, validating the fixed schema/direction/units. */
    public static Budget fromJson(JsonNode root) {
        String schemaVersion = text(root, "schemaVersion");
        if (!EXPECTED_SCHEMA.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "budget.schemaVersion must be '" + EXPECTED_SCHEMA + "': " + schemaVersion);
        }
        String direction = text(root, "direction");
        String units = text(root, "units");
        String metricDirection = text(root, "metricDirection");
        if (!"regression-vs-baseline".equals(direction)) {
            throw new IllegalArgumentException("budget.direction must be 'regression-vs-baseline': " + direction);
        }
        if (!"ns-per-op".equals(units)) {
            throw new IllegalArgumentException("budget.units must be 'ns-per-op': " + units);
        }
        if (!"lower-is-better".equals(metricDirection)) {
            throw new IllegalArgumentException("budget.metricDirection must be 'lower-is-better': " + metricDirection);
        }
        JsonNode thr = require(root, "defaultThreshold");
        double med = thr.path("medianRegressionPct").asDouble(Double.NaN);
        double p95 = thr.path("p95RegressionPct").asDouble(Double.NaN);
        if (Double.isNaN(med) || med < 0) {
            throw new IllegalArgumentException("defaultThreshold.medianRegressionPct must be >= 0");
        }
        if (Double.isNaN(p95) || p95 < 0) {
            throw new IllegalArgumentException("defaultThreshold.p95RegressionPct must be >= 0");
        }
        JsonNode scenarios = require(root, "scenarios");
        Map<String, Boolean> gatedById = new LinkedHashMap<>();
        for (JsonNode s : scenarios) {
            String id = text(s, "id");
            boolean gated = s.path("gated").asBoolean(false);
            gatedById.put(id, gated);
        }
        List<String> ids = List.copyOf(gatedById.keySet());
        List<String> gated = gatedById.entrySet().stream()
                .filter(Map.Entry::getValue).map(Map.Entry::getKey).toList();
        return new Budget(schemaVersion, direction, units, metricDirection, med, p95, ids, gated);
    }

    /** Whether {@code id} is a budget-gated scenario. */
    public boolean isGated(String id) {
        return gatedIds().contains(id);
    }

    private static String text(JsonNode node, String field) {
        return require(node, field).asText();
    }

    private static JsonNode require(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull()) {
            throw new IllegalArgumentException("missing budget field: " + field);
        }
        return child;
    }
}
