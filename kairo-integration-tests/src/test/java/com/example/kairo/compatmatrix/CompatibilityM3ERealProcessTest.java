package com.example.kairo.compatmatrix;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounded real-process tests for the M3-E scenarios (C08 redefine/retransform/hot-update
 * drift, C10 controlled Byte Buddy Agent coexistence). They are opt-in (env
 * {@code KAIRO_COMPAT_REALTEST=true}) and skip - never fake - when the agent artifacts /
 * required JDKs / byte-buddy jar are not available.
 *
 * <p>When they run, they launch a genuinely independent target JVM with the real Kairo
 * agent premain (and, for C08, a real-Instrumentation harness agent; for C10, the
 * controlled Byte Buddy Agent ahead of Kairo) and drive the C08/C10 behavior end-to-end
 * against the agent's real loopback HTTP API. This is a <strong>mechanics</strong> test:
 * it proves the executor and the agent instrument the fixtures correctly (real redefine /
 * retransform / hot-update / drift, and real coexistence) on the current host. Catalog
 * PASSED evidence (a row via {@code run-compatibility.sh}) additionally requires the Linux
 * x86_64 catalog platform; that gate is exercised separately by
 * {@link CompatibilityM3EDispatchTest}.
 *
 * <p>C08 requires JDK 21 (the catalog target). C10 requires JDK 21 AND the byte-buddy jar
 * on the test classpath. Neither is used as compatibility PASSED evidence by itself: the
 * authoritative row evidence is produced by the row runner.
 */
class CompatibilityM3ERealProcessTest {

    @TempDir
    Path tmp;

    @Test
    void c08RealPremainRedefineRetransformHotUpdateDrift() throws Exception {
        realProcessRow("C08", false);
    }

    @Test
    void c10RealPremainControlledByteBuddyAgentCoexistence() throws Exception {
        realProcessRow("C10", true);
    }

    private void realProcessRow(String scenarioId, boolean needsAuxJars) throws Exception {
        // Opt-in: env var (inherited by the forked JVM). Default suites skip this.
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("KAIRO_COMPAT_REALTEST")),
                "set KAIRO_COMPAT_REALTEST=true to run the real-process test");

        Path repo = findRepoRoot();
        Path bootstrap = repo.resolve("kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar");
        Path bootstrapApi = findArtifact(repo.resolve("kairo-bootstrap-api/target"), "kairo-bootstrap-api-");
        Path core = repo.resolve("kairo-agent-core-modern/target/kairo-agent-core-modern.jar");
        Path attach = repo.resolve("kairo-attach-cli/target/kairo-attach.jar");
        Assumptions.assumeTrue(Files.isRegularFile(bootstrap)
                        && Files.isRegularFile(bootstrapApi)
                        && Files.isRegularFile(core) && Files.isRegularFile(attach),
                "agent artifacts not built (run mvn package on the agent modules)");

        CompatibilityScenario scenario = CompatibilityScenarioCatalog.scenario(scenarioId);
        M3EAuxJars auxJars = M3EAuxJars.none();
        Path jdk21 = Path.of(System.getProperty("java.home"));
        RealExecEnv env = new RealExecEnv(System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"), (int) ProcessHandle.current().pid(),
                repo, tmp, Map.of(21, jdk21), bootstrap, bootstrapApi, core, attach,
                120_000L, 60_000L);
        if (needsAuxJars) {
            Path byteBuddy = findClasspathJar("byte-buddy-");
            Assumptions.assumeTrue(byteBuddy != null,
                    "C10 requires the byte-buddy jar on the test classpath");
            String previous = System.getProperty(M3EAuxJars.BYTE_BUDDY_JAR_PROPERTY);
            try {
                System.setProperty(M3EAuxJars.BYTE_BUDDY_JAR_PROPERTY, byteBuddy.toString());
                auxJars = M3EAuxJars.fromProperties();
            } finally {
                if (previous == null) {
                    System.clearProperty(M3EAuxJars.BYTE_BUDDY_JAR_PROPERTY);
                } else {
                    System.setProperty(M3EAuxJars.BYTE_BUDDY_JAR_PROPERTY, previous);
                }
            }
        }

        M3EExecutionOutcome outcome = new RealM3ETargetExecutor().execute(scenario, env, jdk21, auxJars);

        // Diagnostic dump for the opt-in real-process mechanics test: write the full
        // outcome + stdout/stderr artifacts to /tmp so a launch/compile/behavior failure is
        // debuggable (surefire swallows System.out). The dump path is also embedded in every
        // failing assertion message so it is visible in the surefire report.
        boolean allBehaviors = true;
        for (String behavior : scenario.requiredBehaviors()) {
            if (outcome.assertions.stream().noneMatch(a -> behavior.equals(a.name()) && a.passed())) {
                allBehaviors = false;
                break;
            }
        }
        String dumpPath = "/tmp/kairo-m3e-" + scenarioId + "-dump.txt";
        if (!outcome.targetStarted || !outcome.failureReason.isBlank() || !allBehaviors) {
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("scenario=").append(scenarioId).append('\n');
                sb.append("failureReason=").append(outcome.failureReason).append('\n');
                sb.append("targetStarted=").append(outcome.targetStarted).append(" childPid=")
                        .append(outcome.childPid).append(" independent=").append(outcome.independent)
                        .append(" jdk=").append(outcome.targetJdkVersion).append('\n');
                sb.append("launchCommand=").append(outcome.launchCommand).append('\n');
                for (CompatibilityRowRunner.Assertion a : outcome.assertions) {
                    sb.append("  [").append(a.passed() ? "PASS" : "FAIL").append("] ")
                            .append(a.name()).append(" :: ").append(a.detail()).append('\n');
                }
                sb.append("=== stdout artifact ===\n");
                try {
                    sb.append(Files.readString(outcome.stdoutArtifact));
                } catch (Exception ignored) {
                    // best effort
                }
                sb.append("\n=== stderr artifact ===\n");
                try {
                    sb.append(Files.readString(outcome.stderrArtifact));
                } catch (Exception ignored) {
                    // best effort
                }
                Files.writeString(Path.of(dumpPath), sb.toString());
            } catch (Exception ignored) {
                // best effort
            }
        }
        String failHint = scenarioId + " (dump at " + dumpPath + ": failureReason=" + outcome.failureReason + ")";

        // Real independent target process.
        assertThat(outcome.targetStarted).withFailMessage(failHint).isTrue();
        assertThat(outcome.childPid).withFailMessage(failHint).isGreaterThan(0);
        assertThat(outcome.independent).withFailMessage(failHint).isTrue();
        assertThat(outcome.childPid).withFailMessage(failHint).isNotEqualTo(env.runnerPid);
        assertThat(outcome.launchCommand).withFailMessage(failHint).contains("java");
        assertThat(outcome.launchCommand).withFailMessage(failHint).contains("-javaagent:");
        assertThat(Files.isRegularFile(outcome.stdoutArtifact)).withFailMessage(failHint).isTrue();
        assertThat(Files.isRegularFile(outcome.stderrArtifact)).withFailMessage(failHint).isTrue();

        // Every catalog required behavior must be covered by a passed assertion.
        for (String behavior : scenario.requiredBehaviors()) {
            boolean passed = outcome.assertions.stream()
                    .anyMatch(a -> behavior.equals(a.name()) && a.passed());
            assertThat(passed).withFailMessage("real behavior '" + behavior + "' for " + failHint).isTrue();
        }
        assertThat(outcome.failureReason).withFailMessage(failHint).isBlank();

        // Per-scenario subscenario evidence: assert the M3-E-specific behaviors ran.
        if ("C08".equals(scenarioId)) {
            for (String sub : new String[]{
                    "instrumentation.real", "enhance.real", "hotupdate.safe.reconciled",
                    "retransform.real", "redefine.real", "target.drifted",
                    "drift.not.silently.overwritten", "unload.behavior", "execution.order"}) {
                assertThat(outcome.assertions.stream().anyMatch(a -> a.name().equals(sub) && a.passed()))
                        .as("C08 " + sub).isTrue();
            }
        } else if ("C10".equals(scenarioId)) {
            for (String sub : new String[]{
                    "foreign.transform.real", "baseline.foreign.present", "enhance.real",
                    "enhance.preserves.foreign", "update.preserves.foreign",
                    "unload.preserves.foreign.behavior", "unload.preserves.foreign.transformation",
                    "execution.order"}) {
                assertThat(outcome.assertions.stream().anyMatch(a -> a.name().equals(sub) && a.passed()))
                        .as("C10 " + sub).isTrue();
            }
        }
    }

    /** Finds the byte-buddy jar on the test classpath (transitive via kairo-agent-core). */
    private static Path findClasspathJar(String prefix) {
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            String name = Path.of(entry).getFileName().toString();
            if (name.startsWith(prefix) && name.endsWith(".jar")
                    && !name.contains("sources") && !name.contains("javadoc")) {
                return Path.of(entry);
            }
        }
        return null;
    }

    /** Walks up from the working dir to the reactor root (has pom.xml + kairo-agent-bootstrap). */
    private static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path d = dir; d != null; d = d.getParent()) {
            if (Files.isRegularFile(d.resolve("pom.xml"))
                    && Files.isDirectory(d.resolve("kairo-agent-bootstrap"))) {
                return d;
            }
        }
        return dir;
    }

    private static Path findArtifact(Path directory, String prefix) throws Exception {
        if (!Files.isDirectory(directory)) {
            return directory.resolve("missing.jar");
        }
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .filter(path -> !path.getFileName().toString().contains("sources"))
                    .filter(path -> !path.getFileName().toString().contains("javadoc"))
                    .findFirst()
                    .orElse(directory.resolve("missing.jar"));
        }
    }
}
