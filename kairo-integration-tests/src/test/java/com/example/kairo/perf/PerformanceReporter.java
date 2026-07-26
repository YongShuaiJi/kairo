package com.example.kairo.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.kairo.perf.ScenarioCatalog.Scenario;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregates the per-fork raw sample files written by {@link HarnessMain} for the
 * baseline and candidate builds, computes statistics, validates the result, checks
 * it against the tracked budget, and writes {@code benchmark-result.json}.
 *
 * <p>This is the single aggregation point: it reads raw samples (never re-runs the
 * benchmark), so it is deterministic given the same raw inputs. It exits non-zero
 * on harness error (an error marker in a raw file), schema-validation failure, or
 * budget failure.
 *
 * <p>It never writes or relaxes the budget file — only reads it.
 *
 * <p>Exit codes: 0 success (budget passed, schema valid); 5 aggregation/read error;
 * 6 schema validation failure; 7 budget failure.
 */
public final class PerformanceReporter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        try {
            int code = new PerformanceReporter().run(args);
            System.exit(code);
        } catch (Throwable t) {
            t.printStackTrace(System.err);
            System.exit(5);
        }
    }

    /** Package-private entry for in-process tests (no System.exit). Returns the exit code. */
    static int runInProcess(String[] args) throws Exception {
        return new PerformanceReporter().run(args);
    }

    private int run(String[] args) throws Exception {
        Map<String, String> opts = parse(args);
        String mode = required(opts, "mode");
        File budgetFile = new File(required(opts, "budget"));
        File baselineRaw = new File(required(opts, "baseline-raw"));
        File candidateRaw = new File(required(opts, "candidate-raw"));
        String baseBuildId = required(opts, "baseline-build-id");
        String candBuildId = required(opts, "candidate-build-id");
        String baseLabel = opts.getOrDefault("baseline-label", "baseline");
        String candLabel = opts.getOrDefault("candidate-label", "candidate");
        String baseRef = opts.getOrDefault("baseline-source-ref", baseLabel);
        String candRef = opts.getOrDefault("candidate-source-ref", candLabel);
        String jvmArgs = opts.getOrDefault("jvm-args", "");
        String harnessMeta = opts.getOrDefault("harness-meta", "{}");
        File output = new File(required(opts, "output"));

        // Structured command capture (no placeholders). Each side carries the exact
        // build command and the exact harness invocation template/classpath used.
        String baseBuildCmd = required(opts, "baseline-build-command");
        String candBuildCmd = required(opts, "candidate-build-command");
        String baseHarnessCmd = required(opts, "baseline-harness-command");
        String candHarnessCmd = required(opts, "candidate-harness-command");
        String baseClasspath = opts.getOrDefault("baseline-classpath", "");
        String candClasspath = opts.getOrDefault("candidate-classpath", "");

        Budget budget = Budget.fromJson(MAPPER.readTree(budgetFile));

        // Read + group raw fork files by scenario for each build.
        Map<String, List<JsonNode>> baseFiles = readRaw(baselineRaw);
        Map<String, List<JsonNode>> candFiles = readRaw(candidateRaw);

        // Detect harness errors (error markers) up front.
        List<String> harnessErrors = new ArrayList<>();
        collectErrors(baseFiles, "baseline", harnessErrors);
        collectErrors(candFiles, "candidate", harnessErrors);

        List<String> catalogIds = ScenarioCatalog.ids();

        // Build per-scenario stats for each side, and record the per-side raw fork counts.
        Map<String, BudgetChecker.ScenarioStats> baseStats = new LinkedHashMap<>();
        Map<String, BudgetChecker.ScenarioStats> candStats = new LinkedHashMap<>();
        ObjectNode scenariosNode = MAPPER.createObjectNode();
        ArrayNode scenariosArr = scenariosNode.putArray("scenarios");

        for (Scenario sc : ScenarioCatalog.all()) {
            List<JsonNode> baseForScenario = baseFiles.getOrDefault(sc.id(), List.of());
            List<JsonNode> candForScenario = candFiles.getOrDefault(sc.id(), List.of());
            double[] baseSamples = samplesOf(baseForScenario);
            double[] candSamples = samplesOf(candForScenario);
            BudgetChecker.ScenarioStats b = statsOf(baseSamples, sc);
            BudgetChecker.ScenarioStats c = statsOf(candSamples, sc);
            baseStats.put(sc.id(), b);
            candStats.put(sc.id(), c);
            scenariosArr.add(scenarioNode(sc, baseSamples, candSamples, b, c,
                    countValidForks(baseForScenario), countValidForks(candForScenario)));
        }

        // Budget check.
        BudgetChecker checker = new BudgetChecker();
        BudgetChecker.BudgetResult result =
                checker.check(budget, baseStats, candStats, catalogIds);

        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", budget.schemaVersion());
        root.put("generatedAt", java.time.Instant.now().toString());
        root.put("mode", mode);
        root.put("budgetFile", budgetFile.getName());
        root.put("budgetSchemaVersion", budget.schemaVersion());
        root.put("budgetDirection", budget.direction());
        root.put("units", budget.units());
        root.put("metricDirection", budget.metricDirection());
        root.put("defaultThresholdMedianPct", budget.defaultMedianPct());
        root.put("defaultThresholdP95Pct", budget.defaultP95Pct());
        root.set("environment", environment());
        ArrayNode jvmArgsArr = root.putArray("jvmArgs");
        for (String a : jvmArgs.split("\\s+")) {
            if (!a.isEmpty()) {
                jvmArgsArr.add(a);
            }
        }
        try {
            JsonNode meta = MAPPER.readTree(harnessMeta);
            root.set("harness", meta);
        } catch (Exception e) {
            ObjectNode meta = root.putObject("harness");
            meta.put("mainClass", "com.example.kairo.perf.HarnessMain");
        }
        // Required harness fields for PR-mode validation.
        if (root.get("harness") != null && root.get("harness").isObject()) {
            ObjectNode h = (ObjectNode) root.get("harness");
            if (!h.has("forks")) {
                h.put("forks", 0);
            }
            if (!h.has("warmupIterations")) {
                h.put("warmupIterations", 0);
            }
            if (!h.has("measurementIterations")) {
                h.put("measurementIterations", 0);
            }
            if (!h.has("candidateWorkingTreeDirty")) {
                h.put("candidateWorkingTreeDirty", false);
            }
        }

        ObjectNode builds = root.putObject("builds");
        builds.set("baseline", buildNode(baseLabel, baseBuildId, baseRef,
                baseBuildCmd, baseHarnessCmd, baseClasspath));
        builds.set("candidate", buildNode(candLabel, candBuildId, candRef,
                candBuildCmd, candHarnessCmd, candClasspath));

        // Attach comparison verdicts to each scenario node.
        attachComparisons(scenariosArr, result, budget);
        root.set("scenarios", scenariosArr);

        ObjectNode budgetNode = root.putObject("budget");
        budgetNode.put("passed", result.passed());
        budgetNode.put("checkedAt", java.time.Instant.now().toString());
        budgetNode.put("gatedScenarioCount", budget.gatedIds().size());
        ArrayNode failed = budgetNode.putArray("failedScenarios");
        for (String f : result.failedScenarios()) {
            failed.add(f);
        }
        ArrayNode nonCompGated = budgetNode.putArray("nonComparableGatedScenarios");
        for (String n : result.nonComparableGatedScenarios()) {
            nonCompGated.add(n);
        }
        ArrayNode notGated = budgetNode.putArray("notGatedScenarios");
        for (String n : result.notGatedScenarios()) {
            notGated.add(n);
        }
        // Surface harness errors so a reviewer never mistakes them for a clean pass.
        ArrayNode errs = budgetNode.putArray("harnessErrors");
        for (String e : harnessErrors) {
            errs.add(e);
        }

        ObjectNode summary = root.putObject("summary");
        int withSamples = (int) catalogIds.stream()
                .filter(id -> baseStats.get(id).hasSamples() && candStats.get(id).hasSamples())
                .count();
        summary.put("totalScenarios", catalogIds.size());
        summary.put("gatedScenarios", budget.gatedIds().size());
        summary.put("scenariosWithSamples", withSamples);
        summary.put("failedScenarios", result.failedScenarios().size());
        summary.put("nonComparableGatedScenarios", result.nonComparableGatedScenarios().size());
        ArrayNode missing = summary.putArray("missingScenarios");
        for (String id : catalogIds) {
            if (!baseStats.get(id).hasSamples() || !candStats.get(id).hasSamples()) {
                missing.add(id);
            }
        }

        // Write the result (even on failure, so the evidence is inspectable).
        output.getParentFile().mkdirs();
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(output, root);

        // A harness error (error marker in a raw file) means a measurement itself was
        // invalid (e.g. a correctness check failed). That is the most severe failure
        // and is reported before schema/budget so it is never masked.
        if (!harnessErrors.isEmpty()) {
            System.err.println("HARNESS ERRORS:");
            harnessErrors.forEach(e -> System.err.println("  - " + e));
            return 5;
        }
        // Self-validate the schema/content.
        List<String> validationErrors = new ResultValidator().validate(MAPPER.readTree(output), catalogIds);
        if (!validationErrors.isEmpty()) {
            System.err.println("SCHEMA VALIDATION FAILED:");
            validationErrors.forEach(e -> System.err.println("  - " + e));
            return 6;
        }
        if (!result.passed()) {
            System.err.println("BUDGET FAILED:");
            result.verdicts().stream()
                    .filter(v -> v.verdict() == BudgetChecker.Verdict.FAIL
                            || (v.verdict() == BudgetChecker.Verdict.NOT_COMPARABLE && v.gated()))
                    .forEach(v -> System.err.println("  - " + v.scenarioId()
                            + " [" + v.verdict() + "]: " + v.reason()));
            System.out.println("Wrote " + output);
            return 7;
        }
        System.out.println("BUDGET PASSED. Wrote " + output);
        return 0;
    }

    private ObjectNode buildNode(String label, String buildId, String ref,
                                 String buildCommand, String harnessCommand, String classpath) {
        ObjectNode b = MAPPER.createObjectNode();
        b.put("label", label);
        b.put("resolvedBuildId", buildId);
        b.put("sourceRef", ref);
        b.put("buildCommand", buildCommand);
        b.put("harnessCommand", harnessCommand);
        b.put("classpath", classpath);
        return b;
    }

    private ObjectNode scenarioNode(Scenario sc, double[] baseSamples, double[] candSamples,
                                    BudgetChecker.ScenarioStats b, BudgetChecker.ScenarioStats c,
                                    int baseForks, int candForks) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", sc.id());
        node.put("category", sc.category());
        node.put("description", sc.description());
        node.put("comparable", sc.comparable());
        node.put("gated", sc.gated());
        node.put("opsLabel", sc.opsLabel());
        node.put("opsPerIteration", sc.opsPerIteration());
        ObjectNode bNode = statsNode(b, baseSamples);
        bNode.put("forkCount", baseForks);
        ObjectNode cNode = statsNode(c, candSamples);
        cNode.put("forkCount", candForks);
        node.set("baseline", bNode);
        node.set("candidate", cNode);
        return node;
    }

    private ObjectNode statsNode(BudgetChecker.ScenarioStats s, double[] samples) {
        ObjectNode n = MAPPER.createObjectNode();
        if (!s.hasSamples()) {
            n.put("sampleCount", 0);
            n.put("forkCount", 0);
            return n;
        }
        PerfStats ps = PerfStats.of(samples, s.ops());
        n.put("median", ps.median());
        n.put("p95", ps.p95());
        n.put("p99", ps.p99());
        n.put("mean", ps.mean());
        n.put("stddev", ps.sampleStdDev());
        n.put("dispersion", ps.dispersion());
        n.put("sampleCount", ps.sampleCount());
        n.put("ops", s.ops());
        return n;
    }

    private void attachComparisons(ArrayNode scenariosArr, BudgetChecker.BudgetResult result, Budget budget) {
        Map<String, BudgetChecker.ScenarioVerdict> byId = new HashMap<>();
        for (BudgetChecker.ScenarioVerdict v : result.verdicts()) {
            byId.put(v.scenarioId(), v);
        }
        for (JsonNode sc : scenariosArr) {
            ObjectNode node = (ObjectNode) sc;
            BudgetChecker.ScenarioVerdict v = byId.get(sc.get("id").asText());
            ObjectNode cmp = node.putObject("comparison");
            cmp.put("gated", v.gated());
            cmp.put("direction", budget.metricDirection());
            cmp.put("units", budget.units());
            cmp.put("thresholdMedianPct", v.thresholdMedianPct());
            cmp.put("thresholdP95Pct", v.thresholdP95Pct());
            if (v.medianRegressionPct() != null) {
                cmp.put("medianRegressionPct", round(v.medianRegressionPct()));
            }
            if (v.p95RegressionPct() != null) {
                cmp.put("p95RegressionPct", round(v.p95RegressionPct()));
            }
            cmp.put("verdict", v.verdict().name());
            cmp.put("reason", v.reason());
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** Count raw files that are valid samples (not error markers) for a scenario. */
    private int countValidForks(List<JsonNode> files) {
        int n = 0;
        for (JsonNode f : files) {
            if (!f.has("error") && f.has("samples")) {
                n++;
            }
        }
        return n;
    }

    private BudgetChecker.ScenarioStats statsOf(double[] samples, Scenario sc) {
        if (samples.length == 0) {
            return new BudgetChecker.ScenarioStats(0, 0, 0, sc.opsPerIteration());
        }
        PerfStats ps = PerfStats.of(samples, sc.opsPerIteration());
        return new BudgetChecker.ScenarioStats(ps.median(), ps.p95(), ps.sampleCount(), sc.opsPerIteration());
    }

    private double[] samplesOf(List<JsonNode> files) {
        if (files == null) {
            return new double[0];
        }
        List<Double> all = new ArrayList<>();
        for (JsonNode f : files) {
            if (f.has("error")) {
                continue;
            }
            JsonNode arr = f.get("samples");
            if (arr != null && arr.isArray()) {
                for (JsonNode s : arr) {
                    all.add(s.asDouble());
                }
            }
        }
        double[] out = new double[all.size()];
        for (int i = 0; i < all.size(); i++) {
            out[i] = all.get(i);
        }
        return out;
    }

    private Map<String, List<JsonNode>> readRaw(File dir) throws Exception {
        Map<String, List<JsonNode>> byScenario = new LinkedHashMap<>();
        if (!dir.isDirectory()) {
            return byScenario;
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return byScenario;
        }
        for (File f : files) {
            JsonNode node = MAPPER.readTree(f);
            String scenario = node.path("scenario").asText("");
            if (scenario.isEmpty() && !node.has("error")) {
                continue;
            }
            byScenario.computeIfAbsent(scenario, k -> new ArrayList<>()).add(node);
        }
        return byScenario;
    }

    private void collectErrors(Map<String, List<JsonNode>> files, String side, List<String> errors) {
        files.forEach((scenario, list) -> {
            for (JsonNode n : list) {
                if (n.has("error")) {
                    errors.add(side + "/" + scenario + ": " + n.get("error").asText());
                }
            }
        });
    }

    private static ObjectNode environment() {
        ObjectNode env = MAPPER.createObjectNode();
        env.put("jdkVersion", System.getProperty("java.version"));
        env.put("javaVmName", System.getProperty("java.vm.name"));
        env.put("osName", System.getProperty("os.name"));
        env.put("osArch", System.getProperty("os.arch"));
        env.put("osVersion", System.getProperty("os.version"));
        env.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        env.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        return env;
    }

    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required --" + key);
        }
        return v;
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(key, args[++i]);
                } else {
                    opts.put(key, "true");
                }
            }
        }
        return opts;
    }
}
