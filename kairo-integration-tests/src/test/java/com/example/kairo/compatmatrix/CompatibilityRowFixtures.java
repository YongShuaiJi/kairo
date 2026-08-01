package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared row-evidence builders for the aggregator / verifier / runner tests. Keeps the
 * valid shapes in one place so the negative tests can mutate a known-good row. These are
 * test fixtures only - they are NOT C01-C10 scenario fixtures (those land in M3-B..M3-E).
 */
final class CompatibilityRowFixtures {

    static final String BUILD = "0123456789abcdef0123456789abcdef01234567";
    static final String OTHER_BUILD = "fedcba9876543210fedcba9876543210fedcba98";
    static final String COMMAND = "./scripts/v1.7/run-compatibility.sh --scenario %s --output target/v1.7/compatibility-rows/%s.json";
    /** Runner PID for fixtures; distinct from the target child PID. */
    static final int RUNNER_PID = 9999;
    /** Target child PID for PASSED/FAILED fixtures; distinct from RUNNER_PID. */
    static final int CHILD_PID = 4242;

    private final ObjectMapper mapper;

    CompatibilityRowFixtures(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** A valid PASSED row with full real evidence on the catalog platform/JDK. */
    ObjectNode passedRow(String scenarioId) {
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario(scenarioId);
        ObjectNode row = baseRow(s, "PASSED", "");
        ObjectNode env = row.putObject("environment");
        env.put("osName", envNameFor(s.runnerOs()));
        env.put("osArch", envArchFor(s.runnerArch()));
        env.put("jdkVersion", s.targetJdks().get(0) + ".0.11");
        env.put("runnerPid", RUNNER_PID);
        row.putObject("targetJvm")
                .put("pid", CHILD_PID)
                .put("independent", true)
                .put("jdkVersion", s.targetJdks().get(0) + ".0.11");
        ArrayNode assertions = row.putArray("assertions");
        for (String b : s.requiredBehaviors()) {
            assertions.addObject().put("name", b).put("passed", true).put("detail", "ok");
        }
        return row;
    }

    /** A valid NOT_RUN row (no child ran). */
    ObjectNode notRunRow(String scenarioId) {
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario(scenarioId);
        ObjectNode row = baseRow(s, "NOT_RUN", "no fixture implemented; fail-closed per M3-A");
        envBlock(row, s);
        row.putObject("targetJvm").put("pid", 0).put("independent", false).put("jdkVersion", "");
        row.putArray("assertions");
        return row;
    }

    /** A valid EXPERIMENTAL C09 row (no real macOS runner). */
    ObjectNode experimentalC09Row() {
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C09");
        ObjectNode row = baseRow(s, "EXPERIMENTAL", "no real macOS runner available; emitted EXPERIMENTAL");
        envBlock(row, s);
        row.putObject("targetJvm").put("pid", 0).put("independent", false).put("jdkVersion", "");
        row.putArray("assertions");
        return row;
    }

    /** A complete passing matrix: 9 formal PASSED + C09 EXPERIMENTAL (non-blocking). */
    List<JsonNode> passingMatrix() {
        List<JsonNode> rows = new ArrayList<>();
        for (CompatibilityScenario s : CompatibilityScenarioCatalog.all()) {
            if (s.isFormal()) {
                rows.add(passedRow(s.id()));
            } else {
                rows.add(experimentalC09Row());
            }
        }
        return rows;
    }

    /** A complete M3-A matrix: every formal row NOT_RUN + C09 EXPERIMENTAL. */
    List<JsonNode> m3aNotRunMatrix() {
        List<JsonNode> rows = new ArrayList<>();
        for (CompatibilityScenario s : CompatibilityScenarioCatalog.all()) {
            if (s.isFormal()) {
                rows.add(notRunRow(s.id()));
            } else {
                rows.add(experimentalC09Row());
            }
        }
        return rows;
    }

    private ObjectNode baseRow(CompatibilityScenario s, String status, String failureReason) {
        ObjectNode row = mapper.createObjectNode();
        row.put("schemaVersion", CompatibilityScenarioCatalog.SCHEMA_VERSION);
        row.put("catalogVersion", CompatibilityScenarioCatalog.CATALOG_VERSION);
        row.put("scenario", s.id());
        row.put("supportLevel", s.supportLevel().name());
        ObjectNode cat = row.putObject("catalog");
        cat.put("runnerOs", s.runnerOs());
        cat.put("runnerArch", s.runnerArch());
        ArrayNode jdks = cat.putArray("targetJdks");
        for (int j : s.targetJdks()) {
            jdks.add(j);
        }
        cat.put("loadMode", s.loadMode().name());
        cat.put("loadModeRaw", s.loadModeRaw());
        cat.put("fixture", s.fixture());
        cat.put("requiredBehaviorsRaw", s.requiredBehaviorsRaw());
        ArrayNode rb = cat.putArray("requiredBehaviors");
        for (String b : s.requiredBehaviors()) {
            rb.add(b);
        }
        row.put("buildId", BUILD);
        row.put("loadingMode", s.loadModeRaw());
        row.put("fixture", s.fixture());
        row.put("startedAt", "2026-08-01T00:00:00Z");
        row.put("endedAt", "2026-08-01T00:01:00Z");
        row.put("command", String.format(COMMAND, s.id(), s.id()));
        // Provenance: dev mode, clean tree (correction 3).
        row.put("mode", "dev");
        row.put("workingTreeDirty", false);
        row.put("status", status);
        row.put("failureReason", failureReason);
        return row;
    }

    private void envBlock(ObjectNode row, CompatibilityScenario s) {
        ObjectNode env = row.putObject("environment");
        env.put("osName", envNameFor(s.runnerOs()));
        env.put("osArch", envArchFor(s.runnerArch()));
        env.put("jdkVersion", s.targetJdks().get(0) + ".0.11");
        env.put("runnerPid", RUNNER_PID);
    }

    private static String envNameFor(String runnerOs) {
        return "macOS".equals(runnerOs) ? "Mac OS X" : runnerOs;
    }

    private static String envArchFor(String runnerArch) {
        return "arm64".equals(runnerArch) ? "aarch64" : "amd64";
    }
}
