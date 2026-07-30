package com.example.kairo.perf.leak;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic schema/content tests for {@link LeakResultValidator}. Builds valid and
 * mutated {@code leak-result.json} documents directly (no JVM, no agent) and asserts the
 * validator accepts a well-formed result and rejects the specific malformations that
 * would let a fake success, a fabricated/missing Groovy or loader field, or a missing
 * warm-up/gate slip through.
 */
class LeakResultValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUILD_ID = "0123456789abcdef0123456789abcdef01234567";
    private static final List<String> GATE_NAMES = List.of("residual-classloaders", "residual-groovy-loaders",
            "thread-delta", "fd-delta", "heap-growth", "metaspace-growth",
            "rules-cleared", "instrumentation-cleared",
            "snapshot-budget", "journal-budget", "snapshot-cleared-on-close",
            "groovy-cache-budget", "groovy-generation-class-budget", "groovy-cache-cleared-on-close");

    private static ObjectNode validResult(int requestedCycles) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", "1.0");
        root.put("generatedAt", Instant.now().toString());
        root.put("startedAt", Instant.now().toString());
        root.put("endedAt", Instant.now().toString());
        root.put("buildId", BUILD_ID);
        root.put("command", "./scripts/v1.7/run-leak-check.sh --cycles " + requestedCycles + " --output target/v1.7");
        root.put("mode", "pr");
        root.put("workingTreeDirty", false);
        root.putArray("jvmArgs").add("-Xms512m").add("-Xmx512m");
        ObjectNode env = root.putObject("environment");
        env.put("jdkVersion", "21.0.11");
        env.put("osName", "Mac OS X");
        env.put("osArch", "aarch64");
        env.put("availableProcessors", 10);
        env.put("pid", 4242);
        env.put("javacAvailable", true);

        ObjectNode cycles = root.putObject("cycles");
        cycles.put("requested", requestedCycles);
        cycles.put("completed", requestedCycles);
        cycles.put("failed", 0);

        ArrayNode scenarios = root.putArray("scenarios");
        int per = requestedCycles / LeakScenarioCatalog.all().size();
        int remainder = requestedCycles % LeakScenarioCatalog.all().size();
        int idx = 0;
        for (LeakScenarioCatalog.Scenario s : LeakScenarioCatalog.all()) {
            int n = per + (idx < remainder ? 1 : 0);
            ObjectNode sn = scenarios.addObject();
            sn.put("id", s.id());
            sn.put("category", s.category());
            sn.put("description", s.description());
            sn.put("leakSurface", s.leakSurface());
            sn.put("cyclesRequested", n);
            sn.put("cyclesCompleted", n);
            sn.put("cyclesFailed", 0);
            ObjectNode o = sn.putObject("firstOutcome");
            o.put("enhancedBehavior", "BIZ-1");
            o.put("restoredBehavior", "echo:x");
            idx++;
        }

        // Warm-up evidence object (required, registries must be reset to baseline).
        ObjectNode warmup = root.putObject("warmup");
        ArrayNode paths = warmup.putArray("exercisedPaths");
        LeakScenarioCatalog.ids().forEach(paths::add);
        warmup.put("cyclesExecuted", LeakScenarioCatalog.all().size());
        warmup.put("businessTrackedLoaders", 6);
        warmup.put("businessLiveTrackedLoaders", 6);
        warmup.put("businessCollectedLoaders", 0);
        warmup.put("groovyTrackedLoaders", 6);
        warmup.put("groovyLiveTrackedLoaders", 6);
        warmup.put("groovyCollectedLoaders", 0);
        warmup.put("registriesResetToBaseline", true);

        ArrayNode obs = root.putArray("observations");
        obs.add(window("baseline", 0, 0, 0));
        obs.add(window("post-cycles", 0, 0, 0));
        obs.add(window("post-close", 0, 0, 0));

        ObjectNode budgets = root.putObject("budgets");
        LeakBudget b = LeakBudget.DOCUMENTED;
        budgets.put("maxResidualClassLoaders", b.maxResidualClassLoaders());
        budgets.put("maxThreadDelta", b.maxThreadDelta());
        budgets.put("maxFdDelta", b.maxFdDelta());
        budgets.put("maxHeapGrowthPct", b.maxHeapGrowthPct());
        budgets.put("maxMetaspaceGrowthPct", b.maxMetaspaceGrowthPct());
        budgets.put("snapshotMaxEntries", b.snapshotMaxEntries());
        budgets.put("journalMaxRecords", b.journalMaxRecords());
        budgets.put("groovyCacheMaxEntries", b.groovyCacheMaxEntries());
        budgets.put("generationMaxClasses", b.generationMaxClasses());

        ArrayNode gates = root.putArray("gates");
        for (String name : GATE_NAMES) {
            ObjectNode g = gates.addObject();
            g.put("name", name);
            g.put("passed", true);
            g.put("observed", "0");
            g.put("budget", "<= 2");
            g.put("detail", "ok");
        }
        root.putArray("cleanupFailures");
        root.putNull("firstFailure");
        root.put("overall", "PASSED");
        return root;
    }

    private static ObjectNode window(String label, int live, int snapshot, int journal) {
        ObjectNode o = MAPPER.createObjectNode();
        o.put("label", label);
        o.put("postFullGc", true);
        o.put("timestamp", Instant.now().toString());
        o.put("heapUsedBytes", 10_000_000L);
        o.put("metaspaceUsedBytes", 10_000_000L);
        o.put("threadCount", 10);
        o.put("openFdCount", 100L);
        o.put("loadedClassCount", 1000);
        o.put("publishedRuleCount", 0);
        o.put("snapshotCount", snapshot);
        o.put("journalRecordCount", journal);
        o.put("instrumentationTypeCount", 0);
        o.put("instrumentationMethodCount", 0);
        o.put("trackedLoadersTotal", 100);
        o.put("liveTrackedLoaders", live);
        o.put("collectedLoaders", 100 - live);
        writeBucket(o, "measuredBusiness", 100, live, 100 - live);
        writeBucket(o, "measuredGroovy", 0, 0, 0);
        writeBucket(o, "warmupBusiness", 0, 0, 0);
        writeBucket(o, "warmupGroovy", 0, 0, 0);
        o.put("groovyCacheEntries", 0);
        o.put("groovyGenerationCount", 0);
        o.put("groovyMaxClassesInGeneration", 0);
        o.put("groovyGenerationHighWater", 0);
        o.put("groovyLiveTrackedLoaders", 0);
        return o;
    }

    private static void writeBucket(ObjectNode o, String prefix, int tracked, int live, int collected) {
        o.put(prefix + "TrackedLoaders", tracked);
        o.put(prefix + "LiveTrackedLoaders", live);
        o.put(prefix + "CollectedLoaders", collected);
    }

    private static List<String> validate(ObjectNode root, int requestedCycles) {
        return new LeakResultValidator().validate(root, requestedCycles);
    }

    @Test
    void validResultProducesNoErrors() {
        assertThat(validate(validResult(100), 100)).isEmpty();
    }

    @Test
    void wrongSchemaVersionIsRejected() {
        ObjectNode r = validResult(100);
        r.put("schemaVersion", "2.0");
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("schemaVersion"));
    }

    @Test
    void nonHexBuildIdIsRejected() {
        ObjectNode r = validResult(100);
        r.put("buildId", "not-a-commit");
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("buildId"));
    }

    @Test
    void placeholderInCommandIsRejected() {
        ObjectNode r = validResult(100);
        r.put("command", "./run-leak-check.sh --cycles <N> --output target/v1.7");
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("placeholder"));
    }

    @Test
    void dirtyPrTreeIsRejected() {
        ObjectNode r = validResult(100);
        r.put("workingTreeDirty", true);
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("dirty"));
    }

    @Test
    void requestedCyclesMismatchIsRejected() {
        assertThat(validate(validResult(100), 200))
                .anyMatch(e -> e.contains("cycles.requested must equal"));
    }

    @Test
    void missingScenarioIsRejected() {
        ObjectNode r = validResult(100);
        ((ArrayNode) r.get("scenarios")).remove(0);
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("missing scenario"));
    }

    @Test
    void duplicateScenarioIsRejected() {
        ObjectNode r = validResult(100);
        ArrayNode scenarios = (ArrayNode) r.get("scenarios");
        ObjectNode first = (ObjectNode) scenarios.get(0);
        // Replace the second scenario's id with the first's to create a duplicate.
        ((ObjectNode) scenarios.get(1)).put("id", first.get("id").asText());
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("duplicate scenario id"));
    }

    @Test
    void missingRequiredWindowIsRejected() {
        ObjectNode r = validResult(100);
        ArrayNode obs = (ArrayNode) r.get("observations");
        obs.remove(obs.size() - 1);
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("missing required observation window: post-close"));
    }

    @Test
    void missingGroovyFieldIsRejected() {
        ObjectNode r = validResult(100);
        ArrayNode obs = (ArrayNode) r.get("observations");
        ((ObjectNode) obs.get(0)).remove("groovyCacheEntries");
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("groovyCacheEntries must be int"));
    }

    @Test
    void missingLoaderBucketFieldIsRejected() {
        ObjectNode r = validResult(100);
        ArrayNode obs = (ArrayNode) r.get("observations");
        ((ObjectNode) obs.get(0)).remove("measuredBusinessLiveTrackedLoaders");
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("measuredBusinessLiveTrackedLoaders must be int"));
    }

    @Test
    void missingWarmupObjectIsRejected() {
        ObjectNode r = validResult(100);
        r.remove("warmup");
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("warmup"));
    }

    @Test
    void warmupNotResetIsRejected() {
        ObjectNode r = validResult(100);
        ((ObjectNode) r.get("warmup")).put("registriesResetToBaseline", false);
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("registriesResetToBaseline must be true"));
    }

    @Test
    void missingRequiredGateIsRejected() {
        ObjectNode r = validResult(100);
        ArrayNode gates = (ArrayNode) r.get("gates");
        gates.remove(gates.size() - 1); // drop groovy-cache-cleared-on-close
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("missing required gate"));
    }

    @Test
    void tamperedBudgetValueIsRejected() {
        ObjectNode r = validResult(100);
        ((ObjectNode) r.get("budgets")).put("maxResidualClassLoaders", 99);
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("maxResidualClassLoaders must equal"));
    }

    @Test
    void fakeSuccessFails() {
        ObjectNode r = validResult(100);
        // Mark overall PASSED but a gate failed.
        ObjectNode gate = (ObjectNode) ((ArrayNode) r.get("gates")).get(0);
        gate.put("passed", false);
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("fake success"));
    }

    @Test
    void passedWithPresentFirstFailureFails() {
        ObjectNode r = validResult(100);
        ObjectNode ff = r.putObject("firstFailure");
        ff.put("scenario", "gate");
        ff.put("phase", "residual-classloaders");
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("firstFailure is present"));
    }

    @Test
    void failedWithoutFirstFailureFails() {
        ObjectNode r = validResult(100);
        r.put("overall", "FAILED");
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("firstFailure is absent"));
    }

    @Test
    void missingGroovyHighWaterFieldIsRejected() {
        ObjectNode r = validResult(100);
        observation(r, "post-cycles").remove("groovyGenerationHighWater");
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("groovyGenerationHighWater must be int"));
    }

    @Test
    void nonMonotonicGroovyHighWaterIsRejected() {
        ObjectNode r = validResult(100);
        observation(r, "baseline").put("groovyGenerationHighWater", 5);
        // post-cycles/post-close stay 0 -> 0 < 5 is non-decreasing.
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("non-decreasing"));
    }

    @Test
    void groovyHighWaterBelowPointInTimeMaxIsRejected() {
        ObjectNode r = validResult(100);
        observation(r, "post-cycles").put("groovyMaxClassesInGeneration", 5);
        // high-water stays 0 < 5 -> the high-water must dominate the point-in-time max.
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("must be >= groovyMaxClassesInGeneration"));
    }

    @Test
    void generationGateObservedMustReconcileToHighWater() {
        ObjectNode r = validResult(100);
        // A real, monotonic high-water across all windows (dominates maxClasses=0).
        for (JsonNode o : r.path("observations")) {
            ((ObjectNode) o).put("groovyGenerationHighWater", 4);
        }
        // The generation gate observed stays "0" but the max high-water is now 4 -> mismatch.
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("must equal the max groovyGenerationHighWater"));
    }

    @Test
    void residualClassLoadersGateOmittingWarmUpLoadersIsRejected() {
        ObjectNode r = validResult(100);
        ObjectNode postClose = observation(r, "post-close");
        // Warm-up business loaders present (live=3) and counted in the totals, but the gate
        // observed value omits them (0 instead of 3) -> must be rejected.
        postClose.put("warmupBusinessTrackedLoaders", 3);
        postClose.put("warmupBusinessLiveTrackedLoaders", 3);
        postClose.put("warmupBusinessCollectedLoaders", 0);
        postClose.put("liveTrackedLoaders", 3);            // measured 0 + warm-up 3
        postClose.put("trackedLoadersTotal", 103);         // measured 100 + warm-up 3
        setGateObserved(r, "residual-classloaders", "0");  // omits the warm-up loaders
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("warm-up loaders must never be omitted"));
    }

    @Test
    void residualGroovyLoadersGateOmittingWarmUpLoadersIsRejected() {
        ObjectNode r = validResult(100);
        ObjectNode postClose = observation(r, "post-close");
        postClose.put("warmupGroovyTrackedLoaders", 2);
        postClose.put("warmupGroovyLiveTrackedLoaders", 2);
        postClose.put("warmupGroovyCollectedLoaders", 0);
        postClose.put("liveTrackedLoaders", 2);            // measured 0 + warm-up groovy 2
        postClose.put("trackedLoadersTotal", 102);         // measured 100 + warm-up groovy 2
        setGateObserved(r, "residual-groovy-loaders", "0");
        assertThat(validate(r, 100)).anyMatch(e -> e.contains("warm-up loaders must never be omitted"));
    }

    // -------------------------------------------------------- helpers

    private static ObjectNode observation(ObjectNode root, String label) {
        for (JsonNode o : root.path("observations")) {
            if (label.equals(o.path("label").asText())) {
                return (ObjectNode) o;
            }
        }
        throw new AssertionError("observation not found: " + label);
    }

    private static void setGateObserved(ObjectNode root, String name, String observed) {
        for (JsonNode g : root.path("gates")) {
            if (name.equals(g.path("name").asText())) {
                ((ObjectNode) g).put("observed", observed);
                return;
            }
        }
        throw new AssertionError("gate not found: " + name);
    }
}
