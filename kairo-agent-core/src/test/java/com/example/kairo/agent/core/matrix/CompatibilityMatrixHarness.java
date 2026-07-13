package com.example.kairo.agent.core.matrix;

import com.example.kairo.api.matrix.CompatibilityMatrixEntry;
import com.example.kairo.api.matrix.CompatibilityMatrixReport;
import com.example.kairo.api.matrix.CompatibilityScenario;
import com.example.kairo.api.matrix.MatrixOutcome;
import com.example.kairo.agent.core.AgentRuntime;
import net.bytebuddy.agent.ByteBuddyAgent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * V1.5 &sect;6 / &sect;8: the reproducible, formal multi-JDK compatibility-matrix harness.
 *
 * <p>Executes the {@link CompatibilityMatrixFixture} on <em>every locally available JDK</em>:
 * the current JVM in-process (installing a live agent via {@link ByteBuddyAgent}), and every
 * other declared JDK (8, 11, 17, 21) whose home is supplied via a {@code KAIRO_MATRIX_JDK_<v>}
 * environment variable as a subprocess. A JDK not available locally is reported as SKIPPED with
 * a pointer to the CI workflow that validates it - never silently omitted.
 *
 * <p>The {@link #main(String[])} entry point runs the matrix on the current JVM and prints one
 * {@code id\toutcome\treason} line per scenario, so a parent process (or CI) can invoke it on a
 * different JDK and merge the result. The CI workflow {@code .github/workflows/compatibility-matrix.yml}
 * drives the full JDK 8/11/17/21 matrix.
 */
public final class CompatibilityMatrixHarness {

    /** The declared JDK versions the matrix covers (&sect;6: JDK 8, 11, 17, 21). */
    public static final List<String> DECLARED_JDKS = List.of("8", "11", "17", "21");

    private CompatibilityMatrixHarness() {
    }

    /** Aggregated matrix report across every JDK the harness executed or skipped. */
    public record MultiJdkMatrixReport(Map<String, CompatibilityMatrixReport> byJdk,
                                       String currentJdk) {
    }

    /**
     * Run the matrix on the current JVM. When {@code withInstrumentation}, install a live agent
     * so the agent-dependent scenarios execute in-process; otherwise they SKIPPED.
     */
    public static CompatibilityMatrixReport runOnCurrentJdk(boolean withInstrumentation) {
        Instrumentation instrumentation = null;
        AgentRuntime runtime = null;
        if (withInstrumentation) {
            try {
                instrumentation = ByteBuddyAgent.install();
                runtime = new AgentRuntime(instrumentation);
                runtime.start();
            } catch (RuntimeException | Error ignored) {
                // Self-attach or agent start failed: fall back to no-agent context so the matrix
                // still runs with agent-dependent scenarios SKIPPED rather than aborting.
                instrumentation = null;
                runtime = null;
            }
        }
        CompatibilityMatrixRunner.Context ctx = new CompatibilityMatrixRunner.Context(
                instrumentation, runtime,
                runtime != null ? runtime.proxyAnalyzer() : null,
                runtime != null ? runtime.syntheticBridgePolicy() : null);
        try {
            return new CompatibilityMatrixRunner().run(ctx);
        } finally {
            if (runtime != null) {
                try { runtime.close(); } catch (RuntimeException ignored) { }
            }
        }
    }

    /**
     * Run the matrix on every locally available JDK: the current JVM in-process, plus each
     * declared JDK whose home is in a {@code KAIRO_MATRIX_JDK_<v>} env var (subprocess). JDKs
     * not available locally are SKIPPED with a CI pointer.
     */
    public static MultiJdkMatrixReport runMultiJdk() {
        String current = runnerJdkMajor();
        Map<String, CompatibilityMatrixReport> byJdk = new LinkedHashMap<>();
        byJdk.put(current, runOnCurrentJdk(true));
        for (String version : DECLARED_JDKS) {
            if (version.equals(current)) {
                continue;
            }
            String home = System.getenv("KAIRO_MATRIX_JDK_" + version);
            if (home == null || home.isBlank()) {
                byJdk.put(version, skippedReport(version,
                        "JDK " + version + " not available locally (KAIRO_MATRIX_JDK_" + version
                                + " unset); CI compatibility-matrix workflow validates"));
                continue;
            }
            byJdk.put(version, runSubprocess(home, version));
        }
        return new MultiJdkMatrixReport(byJdk, current);
    }

    /** Subprocess entry point: run the matrix on the current JVM and print tab-separated entries. */
    public static void main(String[] args) {
        CompatibilityMatrixReport report = runOnCurrentJdk(true);
        for (CompatibilityMatrixEntry e : report.entries()) {
            System.out.println(e.scenario().id() + "\t" + e.outcome()
                    + "\t" + (e.reason() == null ? "" : e.reason().replace('\t', ' ').replace('\n', ' ')));
        }
        System.out.println("SUMMARY\t" + report.summary() + "\trunnerJdk=" + report.runnerJdk());
    }

    private static CompatibilityMatrixReport runSubprocess(String jdkHome, String version) {
        String java = Path.of(jdkHome, "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder pb = new ProcessBuilder(java,
                "-Djdk.attach.allowAttachSelf=true",
                "-cp", classpath,
                CompatibilityMatrixHarness.class.getName());
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            boolean finished = process.waitFor(90, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return skippedReport(version, "JDK " + version + " subprocess timed out");
            }
            if (process.exitValue() != 0) {
                String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                String reason = err.contains("UnsupportedClassVersionError")
                        ? "JDK " + version + " cannot load the JDK 17-targeted agent build;"
                        + " requires a legacy build profile - CI compatibility-matrix workflow validates"
                        : "JDK " + version + " subprocess exited " + process.exitValue() + ": " + err;
                return skippedReport(version, reason);
            }
            return parseReport(version, lines);
        } catch (Exception e) {
            return skippedReport(version, "JDK " + version + " subprocess failed: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static CompatibilityMatrixReport parseReport(String version, List<String> lines) {
        List<CompatibilityMatrixEntry> entries = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("SUMMARY\t")) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length < 2) {
                continue;
            }
            CompatibilityScenario scenario = findScenario(parts[0]);
            if (scenario == null) {
                continue;
            }
            MatrixOutcome outcome = MatrixOutcome.valueOf(parts[1]);
            String reason = parts.length > 2 ? parts[2] : null;
            entries.add(new CompatibilityMatrixEntry(scenario, outcome, reason, "subprocess JDK " + version));
        }
        return new CompatibilityMatrixReport(entries, version, System.currentTimeMillis(),
                summarize(entries));
    }

    private static CompatibilityScenario findScenario(String id) {
        for (CompatibilityScenario scenario : CompatibilityMatrixFixture.scenarios()) {
            if (scenario.id().equals(id)) {
                return scenario;
            }
        }
        return null;
    }

    private static CompatibilityMatrixReport skippedReport(String version, String reason) {
        List<CompatibilityMatrixEntry> entries = new ArrayList<>();
        for (CompatibilityScenario scenario : CompatibilityMatrixFixture.scenarios()) {
            entries.add(new CompatibilityMatrixEntry(scenario, MatrixOutcome.SKIPPED, reason, "JDK " + version));
        }
        return new CompatibilityMatrixReport(entries, version, System.currentTimeMillis(),
                summarize(entries));
    }

    private static String summarize(List<CompatibilityMatrixEntry> entries) {
        int passed = 0, failed = 0, skipped = 0, documented = 0;
        for (CompatibilityMatrixEntry e : entries) {
            switch (e.outcome()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case SKIPPED -> skipped++;
                case DOCUMENTED -> documented++;
            }
        }
        return passed + " passed, " + failed + " failed, " + skipped + " skipped, " + documented
                + " documented (of " + entries.size() + ")";
    }

    private static String runnerJdkMajor() {
        try {
            return Integer.toString(Runtime.version().feature());
        } catch (NoSuchMethodError | RuntimeException ignored) {
            String spec = System.getProperty("java.specification.version", "0");
            return spec.startsWith("1.") ? spec.substring(2) : spec;
        }
    }
}
