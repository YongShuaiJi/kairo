package com.example.kairo.compatmatrix;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounded real-process test for C09 (agentmain on macOS arm64 / JDK 21). It is opt-in
 * (env {@code KAIRO_COMPAT_REALTEST=true}) and skips - never fakes - when the host is not
 * macOS arm64 / JDK 21 or the agent artifacts are not built. When it runs, it launches a
 * genuinely independent plain-Java target, attaches the real Kairo agent via
 * {@code kairo-attach-cli}, and asserts the real behavior.
 *
 * <p>It is NOT used as compatibility PASSED evidence by itself: it exercises the real
 * executor and requires the complete attach/enhance/unload behavior to pass. The env-var
 * gate keeps the default Maven suites deterministic and process-free.
 */
class CompatibilityPlainJavaRealProcessTest {

    @TempDir
    Path tmp;

    @Test
    void c09RealAttachOnMacOsArm64Jdk21() throws Exception {
        // Opt-in: env var (inherited by the forked JVM). Default suites skip this.
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("KAIRO_COMPAT_REALTEST")),
                "set KAIRO_COMPAT_REALTEST=true to run the real-process test");
        // Truthful host guard: skip (do not fake) unless this is macOS arm64 + JDK 21.
        Assumptions.assumeTrue(isMacOsArm64(), "C09 real-process test is macOS arm64 only");
        Assumptions.assumeTrue(currentJdkMajor() == 21, "C09 real-process test is JDK 21 only");

        Path repo = findRepoRoot();
        Path bootstrap = repo.resolve("kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar");
        Path bootstrapApi = findArtifact(repo.resolve("kairo-bootstrap-api/target"), "kairo-bootstrap-api-");
        Path core = repo.resolve("kairo-agent-core-modern/target/kairo-agent-core-modern.jar");
        Path attach = repo.resolve("kairo-attach-cli/target/kairo-attach.jar");
        Assumptions.assumeTrue(Files.isRegularFile(bootstrap)
                        && Files.isRegularFile(bootstrapApi)
                        && Files.isRegularFile(core) && Files.isRegularFile(attach),
                "agent artifacts not built (run mvn package on the agent modules)");

        Path jdk21 = Path.of(System.getProperty("java.home"));
        RealExecEnv env = new RealExecEnv(System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"), (int) ProcessHandle.current().pid(),
                repo, tmp, Map.of(21, jdk21), bootstrap, bootstrapApi, core, attach,
                60_000L, 30_000L);

        CompatibilityScenario scenario = CompatibilityScenarioCatalog.scenario("C09");
        PlainJavaExecutionOutcome outcome = new RealPlainJavaTargetExecutor().execute(scenario, env, jdk21);

        // Real independent target process.
        assertThat(outcome.targetStarted).isTrue();
        assertThat(outcome.childPid).isGreaterThan(0);
        assertThat(outcome.independent).isTrue();
        assertThat(outcome.childPid).isNotEqualTo(env.runnerPid);
        assertThat(outcome.targetJdkVersion).startsWith("21");
        assertThat(outcome.launchCommand).contains("java");
        assertThat(outcome.attachCommand).contains("kairo-attach");
        assertThat(Files.isRegularFile(outcome.stdoutArtifact)).isTrue();
        assertThat(Files.isRegularFile(outcome.stderrArtifact)).isTrue();

        // The "real attach" behavior must pass on this host.
        boolean attachPassed = outcome.assertions.stream()
                .anyMatch(a -> "真实 attach".equals(a.name()) && a.passed());
        assertThat(attachPassed).as("real external attach").isTrue();

        boolean enhancePassed = outcome.assertions.stream()
                .anyMatch(a -> "增强".equals(a.name()) && a.passed());
        assertThat(enhancePassed).as("real enhance").isTrue();
        boolean unloadPassed = outcome.assertions.stream()
                .anyMatch(a -> "卸载".equals(a.name()) && a.passed());
        assertThat(unloadPassed).as("precise unload and baseline restore").isTrue();
        assertThat(outcome.failureReason).isBlank();
        assertThat(outcome.attachCommand).contains("--bootstrap-jar");

        // The dispatch accepts PASSED only after re-checking every required behavior and
        // process artifact; this is the authoritative real C09 positive path.
        CompatibilityRowRunner.DispatchResult d = PlainJavaScenarioDispatch.run(scenario, env,
                (s, e, j) -> outcome);
        assertThat(d.status()).isEqualTo("PASSED");
        assertThat(d.childPid()).isEqualTo(outcome.childPid);

        // The resulting PASSED row must self-validate with real PID, behaviors and evidence.
        var opts = new CompatibilityCli.RunOptions("C09", tmp.resolve("C09.json").toString(),
                "0123456789abcdef0123456789abcdef01234567",
                "./scripts/v1.7/run-compatibility.sh --scenario C09", "dev", false, false);
        var row = CompatibilityRowRunner.buildRowForResult(opts, scenario,
                System.getProperty("os.name"), System.getProperty("os.arch"),
                System.getProperty("java.version"), env.runnerPid,
                "2026-08-01T00:00:00Z", "2026-08-01T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    private static boolean isMacOsArm64() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        return (os.contains("mac") || os.contains("darwin"))
                && (arch.equals("aarch64") || arch.equals("arm64"));
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
}
