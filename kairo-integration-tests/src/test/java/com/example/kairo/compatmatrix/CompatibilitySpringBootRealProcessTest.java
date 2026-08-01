package com.example.kairo.compatmatrix;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounded real-process tests for C03 (premain) and C04 (external attach) on a Spring
 * Boot 3 executable-jar target. They are opt-in (env {@code KAIRO_COMPAT_REALTEST=true})
 * and skip - never fake - when the host is not Linux x86_64 / JDK 21 or the agent and
 * kairo-demo artifacts are not built. When they run, each launches a genuinely
 * independent Spring Boot 3 executable-jar target JVM, drives the real Kairo agent
 * load path, and asserts the real registration/publication/invocation/unload behavior.
 *
 * <p>These are NOT used as compatibility PASSED evidence by themselves: they exercise
 * the real executor and require the complete behavior to pass. The env-var gate keeps
 * the default Maven suites deterministic and process-free. The authoritative C03/C04
 * row evidence is produced by {@code run-compatibility.sh} on the catalog platform
 * (Linux x86_64 / JDK 21).
 */
class CompatibilitySpringBootRealProcessTest {

    @TempDir
    Path tmp;

    @Test
    void c03RealPremainOnLinuxX8664Jdk21() throws Exception {
        realProcessRow("C03");
    }

    @Test
    void c04RealAttachOnLinuxX8664Jdk21() throws Exception {
        realProcessRow("C04");
    }

    private void realProcessRow(String scenarioId) throws Exception {
        // Opt-in: env var (inherited by the forked JVM). Default suites skip this.
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("KAIRO_COMPAT_REALTEST")),
                "set KAIRO_COMPAT_REALTEST=true to run the real-process test");
        // Truthful host guard: skip (do not fake) unless this is Linux x86_64 + JDK 21.
        Assumptions.assumeTrue(isLinuxX86_64(), scenarioId + " real-process test is Linux x86_64 only");
        Assumptions.assumeTrue(currentJdkMajor() == 21, scenarioId + " real-process test is JDK 21 only");

        Path repo = findRepoRoot();
        Path bootstrap = repo.resolve("kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar");
        Path bootstrapApi = findArtifact(repo.resolve("kairo-bootstrap-api/target"), "kairo-bootstrap-api-");
        Path core = repo.resolve("kairo-agent-core-modern/target/kairo-agent-core-modern.jar");
        Path attach = repo.resolve("kairo-attach-cli/target/kairo-attach.jar");
        Path execJar = findExecJar(repo.resolve("kairo-demo/target"));
        Assumptions.assumeTrue(Files.isRegularFile(bootstrap)
                        && Files.isRegularFile(bootstrapApi)
                        && Files.isRegularFile(core) && Files.isRegularFile(attach)
                        && Files.isRegularFile(execJar),
                "agent + kairo-demo artifacts not built (run mvn package on the agent modules and kairo-demo)");

        Path jdk21 = Path.of(System.getProperty("java.home"));
        RealExecEnv env = new RealExecEnv(System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"), (int) ProcessHandle.current().pid(),
                repo, tmp, Map.of(21, jdk21), bootstrap, bootstrapApi, core, attach,
                90_000L, 45_000L);

        CompatibilityScenario scenario = CompatibilityScenarioCatalog.scenario(scenarioId);
        SpringBootExecutionOutcome outcome = new RealSpringBootTargetExecutor()
                .execute(scenario, env, jdk21, execJar);

        // Real independent target process.
        assertThat(outcome.targetStarted).isTrue();
        assertThat(outcome.childPid).isGreaterThan(0);
        assertThat(outcome.independent).isTrue();
        assertThat(outcome.childPid).isNotEqualTo(env.runnerPid);
        assertThat(outcome.targetJdkVersion).startsWith("21");
        assertThat(outcome.launchCommand).contains("-jar").contains("demo");
        if (scenario.loadMode() != LoadMode.PREMAIN) {
            assertThat(outcome.attachCommand).contains("kairo-attach");
        } else {
            assertThat(outcome.attachCommand).isEmpty();
        }
        assertThat(outcome.launchCommand).contains("--server.port=");
        assertThat(Files.isRegularFile(outcome.stdoutArtifact)).isTrue();
        assertThat(Files.isRegularFile(outcome.stderrArtifact)).isTrue();

        // Every required behavior must pass on this host.
        for (String behavior : scenario.requiredBehaviors()) {
            boolean passed = outcome.assertions.stream()
                    .anyMatch(a -> behavior.equals(a.name()) && a.passed());
            assertThat(passed).as("real behavior '%s' for %s", behavior, scenarioId).isTrue();
        }
        assertThat(outcome.failureReason).as("failureReason for " + scenarioId).isBlank();

        // The dispatch accepts PASSED only after re-checking every required behavior and
        // process artifact; this is the authoritative real positive path.
        CompatibilityRowRunner.DispatchResult d = SpringBootScenarioDispatch.run(scenario, env,
                execJar, (s, e, j, jar) -> outcome);
        assertThat(d.status()).isEqualTo("PASSED");
        assertThat(d.childPid()).isEqualTo(outcome.childPid);

        // The resulting PASSED row must self-validate with real PID, behaviors and evidence.
        var opts = new CompatibilityCli.RunOptions(scenarioId, tmp.resolve(scenarioId + ".json").toString(),
                "0123456789abcdef0123456789abcdef01234567",
                "./scripts/v1.7/run-compatibility.sh --scenario " + scenarioId, "dev", false, false);
        var row = CompatibilityRowRunner.buildRowForResult(opts, scenario,
                System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"), env.runnerPid,
                "2026-08-01T00:00:00Z", "2026-08-01T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    private static boolean isLinuxX86_64() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return os.contains("linux")
                && (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64"));
    }

    private static int currentJdkMajor() {
        return PlatformNormals.majorJdk(System.getProperty("java.version"));
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

    /** Finds the kairo-demo Spring Boot executable jar (the {@code -exec} classifier). */
    private static Path findExecJar(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return directory.resolve("missing-exec.jar");
        }
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("kairo-demo-"))
                    .filter(path -> path.getFileName().toString().endsWith("-exec.jar"))
                    .findFirst()
                    .orElse(directory.resolve("missing-exec.jar"));
        }
    }
}
