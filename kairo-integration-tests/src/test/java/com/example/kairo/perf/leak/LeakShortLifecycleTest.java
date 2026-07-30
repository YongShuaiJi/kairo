package com.example.kairo.perf.leak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Short real lifecycle run for the M2-C harness: runs the harness in-process with the
 * minimum cycle count (6 = one cycle per scenario, including the Byte Buddy generated
 * class) against a real ByteBuddyAgent +
 * AgentRuntime, then asserts the evidence is schema-valid and that the harness
 * passes every documented M2-C leak budget.
 *
 * <p>The harness strictly enforces the documented &sect;9.3 budgets (no weakening).
 * This is the in-JVM counterpart of
 * {@code ./scripts/v1.7/run-leak-check.sh --cycles 500 --output target/v1.7}.
 */
class LeakShortLifecycleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUILD_ID = "0123456789abcdef0123456789abcdef01234567";

    @TempDir
    Path outputDir;

    @Test
    void harnessPassesLeakGateWithValidEvidence() throws Exception {
        String cmd = "./scripts/v1.7/run-leak-check.sh --cycles 6 --output " + outputDir;
        int rc = LeakCheckHarness.runInProcess(new String[]{
                "--cycles", "6",
                "--output", outputDir.toString(),
                "--build-id", BUILD_ID,
                "--command", cmd,
                "--jvm-args", "-Xms512m -Xmx512m -XX:+AlwaysPreTouch",
                "--mode", "pr",
                "--working-tree-dirty", "false"
        });

        Path resultFile = outputDir.resolve("leak-result.json");
        assertThat(resultFile).exists();

        JsonNode result = MAPPER.readTree(Files.readString(resultFile));
        // Schema must be valid regardless of the gate outcome.
        List<String> errors = new LeakResultValidator().validate(result, 6);
        assertThat(errors).as("result schema errors").isEmpty();

        // Cycles all executed (the lifecycle logic is correct); the leak is the only
        // failure, captured as a gate failure rather than a lifecycle failure.
        assertThat(result.path("cycles").path("completed").asInt()).isEqualTo(6);
        assertThat(result.path("cycles").path("failed").asInt()).isZero();
        for (String id : LeakScenarioCatalog.ids()) {
            JsonNode s = findScenario(result, id);
            assertThat(s.path("cyclesCompleted").asInt())
                    .as(id + " cyclesCompleted").isEqualTo(s.path("cyclesRequested").asInt());
            assertThat(s.path("cyclesFailed").asInt()).as(id + " cyclesFailed").isZero();
        }

        assertThat(rc).as("all leak gates must pass").isZero();
        assertThat(result.path("overall").asText()).isEqualTo("PASSED");
        JsonNode firstFailure = result.path("firstFailure");
        assertThat(firstFailure.isMissingNode() || firstFailure.isNull())
                .as("a passing run must not report firstFailure")
                .isTrue();

        JsonNode residual = findGate(result, "residual-classloaders");
        assertThat(residual.path("passed").asBoolean())
                .as("residual-classloaders gate").isTrue();
        int residualObserved = Integer.parseInt(residual.path("observed").asText());
        assertThat(residualObserved)
                .as("residual unloadable ClassLoaders stay within the documented budget")
                .isLessThanOrEqualTo(LeakBudget.DOCUMENTED.maxResidualClassLoaders());

        // Warm-up evidence contract (§9.3): an explicit warm-up phase ran every measured path
        // before the baseline, asserted the registries reset, and is accounted separately from
        // the requested cycles (never folded into the measured residual gate).
        JsonNode warmup = result.path("warmup");
        assertThat(warmup.isObject()).as("warmup object present").isTrue();
        assertThat(warmup.path("registriesResetToBaseline").asBoolean())
                .as("warm-up must reset rule/instrumentation registries before baseline").isTrue();
        assertThat(warmup.path("cyclesExecuted").asInt())
                .as("warm-up ran one cycle per measured path")
                .isEqualTo(LeakScenarioCatalog.all().size());
        java.util.Set<String> exercised = new java.util.HashSet<>();
        for (JsonNode p : warmup.path("exercisedPaths")) {
            exercised.add(p.asText());
        }
        assertThat(exercised).as("warm-up exercised every scenario path once")
                .containsExactlyInAnyOrderElementsOf(LeakScenarioCatalog.ids());
        // Warm-up loaders are tracked in their own bucket (one business loader per warm-up path).
        assertThat(warmup.path("businessTrackedLoaders").asInt())
                .as("warm-up tracked its business loaders separately").isGreaterThanOrEqualTo(exercised.size());

        // The post-close observation splits business/Groovy × warm-up/measured. Per §9.3 the
        // residual budget includes EVERY explicitly created loader of a kind, so the
        // residual-classloaders gate must reconcile to measured + warm-up business live (warm-up
        // loaders can never be present yet omitted), and the Groovy loaders are tracked too.
        JsonNode postClose = findObservation(result, "post-close");
        assertThat(postClose.path("measuredBusinessTrackedLoaders").asInt())
                .as("measured business loaders tracked").isGreaterThanOrEqualTo(exercised.size());
        assertThat(postClose.path("measuredGroovyTrackedLoaders").asInt())
                .as("real KairoGroovyClassLoader instances tracked").isGreaterThanOrEqualTo(exercised.size());
        assertThat(postClose.path("warmupBusinessTrackedLoaders").asInt())
                .as("warm-up business loaders tracked separately from measured")
                .isGreaterThanOrEqualTo(exercised.size());
        int measuredBizLive = postClose.path("measuredBusinessLiveTrackedLoaders").asInt();
        int warmupBizLive = postClose.path("warmupBusinessLiveTrackedLoaders").asInt();
        assertThat(residualObserved)
                .as("residual-classloaders reconciles to measured + warm-up business live")
                .isEqualTo(measuredBizLive + warmupBizLive)
                .as("warm-up loaders are included in the residual budget, never omitted")
                .isLessThanOrEqualTo(LeakBudget.DOCUMENTED.maxResidualClassLoaders());

        // The Groovy generation high-water is a real, run-scoped measurement (not a fabricated
        // zero): warm-up + measured cycles compiled Groovy rules, so the high-water is strictly
        // positive and bounded by the product's MAX_CLASSES_PER_GENERATION (<= 256), and the
        // generation-class gate observes it.
        int genHighWater = postClose.path("groovyGenerationHighWater").asInt();
        assertThat(genHighWater)
                .as("groovy generation high-water is real, not a fabricated zero")
                .isGreaterThan(0)
                .isLessThanOrEqualTo(LeakBudget.DOCUMENTED.generationMaxClasses());
        JsonNode genGate = findGate(result, "groovy-generation-class-budget");
        assertThat(Integer.parseInt(genGate.path("observed").asText()))
                .as("generation gate observed reconciles to the run high-water")
                .isEqualTo(genHighWater)
                .isGreaterThan(0);

        // Groovy diagnostics are real (measured, not fabricated): at least one generation/cache
        // entry existed during the run, and they cleared on close.
        JsonNode groovyClose = findGate(result, "groovy-cache-cleared-on-close");
        assertThat(groovyClose.path("passed").asBoolean())
                .as("Groovy cache + generations cleared on close").isTrue();
    }

    private static JsonNode findObservation(JsonNode result, String label) {
        for (JsonNode o : result.path("observations")) {
            if (label.equals(o.path("label").asText())) {
                return o;
            }
        }
        throw new AssertionError("observation not found in result: " + label);
    }

    private static JsonNode findScenario(JsonNode result, String id) {
        for (JsonNode s : result.path("scenarios")) {
            if (id.equals(s.path("id").asText())) {
                return s;
            }
        }
        throw new AssertionError("scenario not found in result: " + id);
    }

    private static JsonNode findGate(JsonNode result, String name) {
        for (JsonNode g : result.path("gates")) {
            if (name.equals(g.path("name").asText())) {
                return g;
            }
        }
        throw new AssertionError("gate not found in result: " + name);
    }
}
