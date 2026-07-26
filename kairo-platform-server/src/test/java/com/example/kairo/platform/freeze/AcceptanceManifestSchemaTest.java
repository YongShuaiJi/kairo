package com.example.kairo.platform.freeze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 &sect;5.2 acceptance manifest schema 2.0 invariants. The manifest is the gate index for
 * every requirement across M0&sim;M6; this test guards its nested-gates shape so a hand-edit cannot
 * silently drop a gate, self-certify an un-run gate, or lose the M0 historical facts.
 *
 * <p>Compare-only: it reads the committed {@code v1.7-acceptance-manifest.json} at the repository
 * root and asserts structural invariants. It never writes.
 */
class AcceptanceManifestSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> GATE_LEVELS = List.of("PR", "RC", "RELEASE");
    private static final Set<String> ALLOWED_STATUSES =
            Set.of("PASSED", "FAILED", "SKIPPED", "NOT_RUN", "EXPERIMENTAL");
    private static final String M0_BUILD_ID = "cfb6ec70a499a6b8d689272f996aa7291e8f8c80";

    @Test
    void manifestHasSchema2NestedGatesAndUnchangedM0Facts() throws Exception {
        JsonNode manifest = readManifest();

        // --- Top-level release/baseline facts preserved from schema 1.0. ---
        assertThat(manifest.get("schemaVersion").asText()).isEqualTo("2.0");
        assertThat(manifest.get("release").asText()).isEqualTo("V1.7.0");
        assertThat(manifest.get("buildId").asText()).isNotBlank();
        assertThat(manifest.get("generatedAt").asText()).isNotBlank();
        JsonNode baseline = manifest.get("sourceBaseline");
        assertThat(baseline.get("version").asText()).isEqualTo("V1.6.0");
        assertThat(baseline.get("commit").asText())
                .isEqualTo("113823b41981a2d8fb5473a772ae2d2938d9582e");
        assertThat(baseline.get("openApiSha256").asText())
                .isEqualTo("aefb4f9b7d1eb5855e3c6218e503bdaf419de1e0edc41083e49946ef39684400");

        JsonNode requirements = manifest.get("requirements");
        assertThat(requirements.isArray()).isTrue();
        assertThat(requirements).isNotEmpty();

        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode req : requirements) {
            String id = req.get("id").asText();
            assertThat(ids.add(id)).as("duplicate requirement id: " + id).isTrue();
            assertRequirement(req, id);
        }

        // --- Required requirements exist. ---
        assertThat(ids).contains("V17-CONTRACT", "V17-RECOVERY", "V17-UPGRADE", "V17-SOAK",
                "V17-PERF", "V17-COMPAT", "V17-OPS", "V17-SUPPLY", "V17-LTS", "V17-REGRESSION");

        // --- M0 PASSED gates survived the migration; later PR gates may join only
        // after Codex certifies their milestone evidence. ---
        List<String> passedPr = new ArrayList<>();
        for (JsonNode req : requirements) {
            JsonNode pr = req.get("gates").get("PR");
            if ("PASSED".equals(pr.get("status").asText())) {
                passedPr.add(req.get("id").asText());
            }
        }
        assertThat(passedPr).contains("V17-CONTRACT", "V17-UPGRADE", "V17-REGRESSION");
        assertThat(passedPr).allMatch(id -> Set.of(
                "V17-CONTRACT", "V17-UPGRADE", "V17-REGRESSION",
                "V17-RECOVERY", "V17-PERF").contains(id));

        for (String id : List.of("V17-CONTRACT", "V17-UPGRADE", "V17-REGRESSION")) {
            JsonNode gate = findRequirement(requirements, id).get("gates").get("PR");
            assertThat(gate.get("buildId").asText())
                    .as(id + ".PR must retain its M0 tested build")
                    .isEqualTo(M0_BUILD_ID);
        }
    }

    private void assertRequirement(JsonNode req, String id) {
        assertThat(req.get("gates").isObject()).isTrue();
        Iterator<String> gateNames = req.get("gates").fieldNames();
        Set<String> present = new LinkedHashSet<>();
        gateNames.forEachRemaining(present::add);
        assertThat(present).as(id + " gates must be exactly PR/RC/RELEASE")
                .containsExactlyInAnyOrderElementsOf(GATE_LEVELS);

        for (String level : GATE_LEVELS) {
            JsonNode gate = req.get("gates").get(level);
            String status = gate.get("status").asText();
            assertThat(ALLOWED_STATUSES).as(id + "." + level + " status").contains(status);

            // Completed gates must carry concrete evidence. RC/RELEASE remain unexecuted during
            // PR implementation and certification.
            if ("PASSED".equals(status) || "FAILED".equals(status)) {
                assertThat(gate.get("commands").isArray()).isTrue();
                assertThat(gate.get("commands")).as(id + "." + level + " commands").isNotEmpty();
                assertThat(gate.get("reports").isArray()).isTrue();
                assertThat(gate.get("reports")).as(id + "." + level + " reports").isNotEmpty();
                assertThat(gate.get("startedAt").isNull()).isFalse();
                assertThat(gate.get("endedAt").isNull()).isFalse();
                assertThat(gate.get("buildId").asText())
                        .as(id + "." + level + " tested build").isNotBlank();
                assertThat(level).as(id + "." + level + " is not a self-certifiable gate yet")
                        .isEqualTo("PR");
            } else {
                assertThat(gate.get("commands").isArray()).isTrue();
                assertThat(gate.get("commands")).as(id + "." + level + " commands").isEmpty();
                assertThat(gate.get("reports").isArray()).isTrue();
                assertThat(gate.get("reports")).as(id + "." + level + " reports").isEmpty();
                assertThat(gate.get("startedAt").isNull()).isTrue();
                assertThat(gate.get("endedAt").isNull()).isTrue();
            }
        }

        assertThat(req.get("gates").get("RC").get("status").asText()).isEqualTo("NOT_RUN");
        assertThat(req.get("gates").get("RELEASE").get("status").asText()).isEqualTo("NOT_RUN");
    }

    private static JsonNode findRequirement(JsonNode requirements, String id) {
        for (JsonNode requirement : requirements) {
            if (id.equals(requirement.get("id").asText())) {
                return requirement;
            }
        }
        throw new AssertionError("missing requirement: " + id);
    }

    private static JsonNode readManifest() throws Exception {
        // The manifest is a tracked file at the repository root; under surefire the module
        // working directory is kairo-platform-server, so the root is its parent.
        Path moduleDir = Paths.get("").toAbsolutePath();
        Path manifest = moduleDir.getParent().resolve("v1.7-acceptance-manifest.json");
        assertThat(Files.exists(manifest))
                .as("manifest not found at " + manifest).isTrue();
        try (var in = Files.newInputStream(manifest)) {
            return MAPPER.readTree(in);
        }
    }
}
