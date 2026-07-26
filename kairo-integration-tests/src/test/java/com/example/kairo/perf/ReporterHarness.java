package com.example.kairo.perf;

import java.io.File;

/**
 * In-process adapter that invokes {@link PerformanceReporter#runInProcess} with the
 * same arguments the shell script would pass, capturing its exit code. Test-only:
 * lets deterministic tests drive the reporter's aggregation + validation + budget
 * check against fixture raw files without spawning a JVM or a System.exit.
 *
 * <p>Exit codes mirror the reporter contract: 0 passed; 5 harness error;
 * 6 schema validation failure; 7 budget failure.
 *
 * <p>The harness-meta {@code forks} field is set to 5 to match the fixtures written
 * by {@link RawFileCountTest}. The validator enforces that every per-side forkCount
 * equals harness.forks, so the fixtures and the meta must agree.
 */
final class ReporterHarness {

    private ReporterHarness() { }

    static int run(File baseRaw, File candRaw, File output) {
        File repoRoot = findRepoRoot();
        File budget = new File(repoRoot, "v1.7-performance-budget.json");
        String[] args = {
                "--mode", "smoke",
                "--budget", budget.getAbsolutePath(),
                "--baseline-raw", baseRaw.getAbsolutePath(),
                "--candidate-raw", candRaw.getAbsolutePath(),
                "--baseline-build-id", "113823b41981a2d8fb5473a772ae2d2938d9582e",
                "--candidate-build-id", "b29683c4b50681298d2a462c8da4ec982c9cf2cf",
                "--baseline-label", "V1.6.0",
                "--candidate-label", "HEAD",
                "--baseline-source-ref", "V1.6.0",
                "--candidate-source-ref", "HEAD",
                "--baseline-build-command", "cd /tmp/baseline && mvn -B -ntp -pl kairo-integration-tests -am test-compile",
                "--candidate-build-command", "cd /tmp/candidate && mvn -B -ntp -pl kairo-integration-tests -am test-compile",
                "--baseline-harness-command", "java -Xmx512m -cp /cp com.example.kairo.perf.HarnessMain --scenario s --warmup 2 --measure 5",
                "--candidate-harness-command", "java -Xmx512m -cp /cp com.example.kairo.perf.HarnessMain --scenario s --warmup 2 --measure 5",
                "--baseline-classpath", "/cp:/baseline/kairo-core/target/classes",
                "--candidate-classpath", "/cp:/candidate/kairo-core/target/classes",
                "--jvm-args", "-Xmx512m",
                "--harness-meta",
                "{\"mainClass\":\"com.example.kairo.perf.HarnessMain\",\"forks\":5,\"warmupIterations\":2,\"measurementIterations\":20,\"candidateWorkingTreeDirty\":false}",
                "--output", output.getAbsolutePath(),
        };
        try {
            return PerformanceReporter.runInProcess(args);
        } catch (Exception e) {
            throw new AssertionError("reporter threw", e);
        }
    }

    /** Walk up to find the repo root (the dir containing the tracked budget file). */
    private static File findRepoRoot() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (int i = 0; i < 10; i++) {
            if (new File(dir, "v1.7-performance-budget.json").isFile()) {
                return dir;
            }
            dir = dir.getParentFile();
            if (dir == null) {
                break;
            }
        }
        throw new AssertionError("repo root (v1.7-performance-budget.json) not found");
    }
}
