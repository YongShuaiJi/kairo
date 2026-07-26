package com.example.kairo.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.kairo.perf.ScenarioCatalog.Scenario;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Asserts the scenario catalog covers every item of the §9.1 measurement matrix,
 * that ids are unique and well-formed, and that the tracked budget file is
 * consistent with the catalog (same scenario set, explicit direction/units).
 */
class ScenarioCompletenessTest {

    @Test
    void catalogCoversFullMatrix() {
        Set<String> ids = new HashSet<>(ScenarioCatalog.ids());
        // §9.1 matrix items.
        assertThat(ids).contains("no-agent-baseline");
        assertThat(ids).contains("agent-loaded-not-enhanced");
        assertThat(ids).contains("enhanced-rule-not-matched");
        assertThat(ids).contains("before-hit");
        assertThat(ids).contains("return-hit");
        assertThat(ids).contains("throws-hit");
        assertThat(ids).contains("groovy-noop");
        assertThat(ids).contains("groovy-arg-read");
        assertThat(ids).contains("groovy-return-replace");
        assertThat(ids).contains("chain-1");
        assertThat(ids).contains("chain-5");
        assertThat(ids).contains("chain-20");
        assertThat(ids).contains("inventory-query");
        assertThat(ids).contains("event-buffer-full-drop");
    }

    @Test
    void idsAreUnique() {
        List<String> ids = ScenarioCatalog.ids();
        assertThat(ids).hasSize(new HashSet<>(ids).size());
    }

    @Test
    void everyScenarioIsWellFormed() {
        for (Scenario s : ScenarioCatalog.all()) {
            assertThat(s.id()).isNotBlank();
            assertThat(s.description()).isNotBlank();
            assertThat(s.opsPerIteration()).isPositive();
            assertThat(s.opsLabel()).isNotBlank();
            assertThat(s.comparable()).isTrue(); // all current scenarios have a V1.6.0 baseline
        }
    }

    @Test
    void gatedSetMatchesKeyHitMissPaths() {
        // §9.1: the 20% budget gates the KEY hit/miss paths, not noisy baselines or
        // every observational scenario. Exactly 10 gated, 4 observed-only.
        assertThat(ScenarioCatalog.gatedIds()).containsExactlyInAnyOrder(
                "enhanced-rule-not-matched", "before-hit", "return-hit", "throws-hit",
                "groovy-noop", "groovy-arg-read", "groovy-return-replace",
                "chain-1", "chain-5", "chain-20");
        assertThat(ScenarioCatalog.observedOnlyIds()).containsExactlyInAnyOrder(
                "no-agent-baseline", "agent-loaded-not-enhanced",
                "inventory-query", "event-buffer-full-drop");
        assertThat(ScenarioCatalog.gatedIds()).hasSize(10);
        assertThat(ScenarioCatalog.observedOnlyIds()).hasSize(4);
    }

    @Test
    void catalogHasExactlyFourteenScenarios() {
        assertThat(ScenarioCatalog.all()).hasSize(14);
    }

    @Test
    void chainScenariosCoverOneFiveTwenty() {
        assertThat(ScenarioCatalog.get("chain-1").opsPerIteration())
                .isEqualTo(ScenarioCatalog.get("chain-20").opsPerIteration());
    }

    @Test
    void budgetFileMatchesCatalogAndDeclaresDirection() throws Exception {
        File budget = findBudgetFile();
        JsonNode root = new ObjectMapper().readTree(budget);

        assertThat(root.get("schemaVersion").asText()).isEqualTo("1.0");
        assertThat(root.get("direction").asText()).isEqualTo("regression-vs-baseline");
        assertThat(root.get("units").asText()).isEqualTo("ns-per-op");
        assertThat(root.get("metricDirection").asText()).isEqualTo("lower-is-better");
        assertThat(root.get("defaultThreshold").get("medianRegressionPct").asDouble()).isEqualTo(20.0);
        assertThat(root.get("defaultThreshold").get("p95RegressionPct").asDouble()).isEqualTo(20.0);

        Set<String> budgetIds = new HashSet<>();
        Set<String> gatedIds = new HashSet<>();
        for (JsonNode s : root.get("scenarios")) {
            budgetIds.add(s.get("id").asText());
            if (s.get("gated").asBoolean(false)) {
                gatedIds.add(s.get("id").asText());
            }
        }
        assertThat(budgetIds).containsExactlyInAnyOrderElementsOf(ScenarioCatalog.ids());
        assertThat(gatedIds).containsExactlyInAnyOrderElementsOf(ScenarioCatalog.gatedIds());
    }

    @Test
    void budgetFileParsesThroughModel() throws Exception {
        File budget = findBudgetFile();
        Budget parsed = Budget.fromJson(new ObjectMapper().readTree(budget));
        assertThat(parsed.schemaVersion()).isEqualTo("1.0");
        assertThat(parsed.defaultMedianPct()).isEqualTo(20.0);
        assertThat(parsed.scenarioIds()).containsExactlyElementsOf(ScenarioCatalog.ids());
        assertThat(parsed.gatedIds()).containsExactlyElementsOf(ScenarioCatalog.gatedIds());
        assertThat(parsed.isGated("before-hit")).isTrue();
        assertThat(parsed.isGated("no-agent-baseline")).isFalse();
    }

    @Test
    void budgetModelRejectsWrongSchemaVersion() throws Exception {
        File budget = findBudgetFile();
        JsonNode root = new ObjectMapper().readTree(budget).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) root).put("schemaVersion", "2.0");
        assertThatThrownBy(() -> Budget.fromJson(root))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    /** Walk up from the test working directory to find the tracked budget file. */
    private static File findBudgetFile() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 8; i++) {
            File f = new File(dir, "v1.7-performance-budget.json");
            if (f.isFile()) {
                return f;
            }
            dir = dir.getParentFile();
            if (dir == null) {
                break;
            }
        }
        throw new AssertionError("v1.7-performance-budget.json not found from " + System.getProperty("user.dir"));
    }
}
