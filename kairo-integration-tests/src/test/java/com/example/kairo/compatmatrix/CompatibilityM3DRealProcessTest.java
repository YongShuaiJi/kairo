package com.example.kairo.compatmatrix;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bounded real-process tests for the M3-D scenarios (C05 ClassLoader, C06 proxy,
 * C07 lambda/bridge/synthetic). They are opt-in (env {@code KAIRO_COMPAT_REALTEST=true})
 * and skip - never fake - when the agent artifacts / required JDKs are not available.
 *
 * <p>When they run, they launch a genuinely independent target JVM with the real Kairo
 * agent premain and drive the C05/C06/C07 behavior end-to-end against the agent's real
 * loopback HTTP API. This is a <strong>mechanics</strong> test: it proves the executor
 * and the agent instrument the fixtures correctly (real enhance / invoke / unload) on
 * the current host. Catalog PASSED evidence (a row via {@code run-compatibility.sh})
 * additionally requires the Linux x86_64 catalog platform; that gate is exercised
 * separately by {@link CompatibilityM3DDispatchTest}.
 *
 * <p>C07 additionally requires JDK 17 AND JDK 21 (two target JVMs); the test skips if
 * either JDK is unavailable on this host. It is NOT used as compatibility PASSED
 * evidence by itself: the authoritative row evidence is produced by the row runner.
 */
class CompatibilityM3DRealProcessTest {

    @TempDir
    Path tmp;

    @Test
    void c05RealPremainParentChildLoaders() throws Exception {
        realProcessRow("C05", false);
    }

    @Test
    void c06RealPremainProxyTypes() throws Exception {
        realProcessRow("C06", true);
    }

    @Test
    void c07RealPremainLambdaBridgeSynthetic() throws Exception {
        realProcessRow("C07", false);
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
        M3DAuxJars auxJars = M3DAuxJars.none();
        Path jdk21 = Path.of(System.getProperty("java.home"));
        RealExecEnv env;
        if ("C07".equals(scenarioId)) {
            // C07 needs JDK 17 AND JDK 21. Resolve JDK 17 truthfully; skip if unavailable.
            Path jdk17 = resolveJdk(17);
            Assumptions.assumeTrue(jdk17 != null,
                    "C07 requires JDK 17 AND JDK 21; set KAIRO_JDK17_HOME or /usr/libexec/java_home -v 17");
            env = new RealExecEnv(System.getProperty("os.name"), System.getProperty("os.arch"),
                    System.getProperty("java.version"), (int) ProcessHandle.current().pid(),
                    repo, tmp, Map.of(17, jdk17, 21, jdk21), bootstrap, bootstrapApi, core, attach,
                    90_000L, 45_000L);
        } else {
            env = new RealExecEnv(System.getProperty("os.name"), System.getProperty("os.arch"),
                    System.getProperty("java.version"), (int) ProcessHandle.current().pid(),
                    repo, tmp, Map.of(21, jdk21), bootstrap, bootstrapApi, core, attach,
                    90_000L, 45_000L);
        }
        if (needsAuxJars) {
            Path byteBuddy = findByteBuddyJar();
            Path springCore = findSpringCoreJar();
            Assumptions.assumeTrue(byteBuddy != null && springCore != null,
                    "C06 requires byte-buddy and spring-core jars on the test classpath");
            System.setProperty(M3DAuxJars.BYTE_BUDDY_JAR_PROPERTY, byteBuddy.toString());
            System.setProperty(M3DAuxJars.SPRING_CORE_JAR_PROPERTY, springCore.toString());
            auxJars = M3DAuxJars.fromProperties();
        }

        M3DExecutionOutcome outcome = new RealM3DTargetExecutor().execute(scenario, env, jdk21, auxJars);

        // Real independent target process.
        assertThat(outcome.targetStarted).as(scenarioId + " targetStarted").isTrue();
        assertThat(outcome.childPid).as(scenarioId + " childPid").isGreaterThan(0);
        assertThat(outcome.independent).as(scenarioId + " independent").isTrue();
        assertThat(outcome.childPid).isNotEqualTo(env.runnerPid);
        assertThat(outcome.launchCommand).as(scenarioId + " launchCommand").contains("java");
        assertThat(outcome.launchCommand).as(scenarioId + " premain").contains("-javaagent:");
        assertThat(Files.isRegularFile(outcome.stdoutArtifact)).isTrue();
        assertThat(Files.isRegularFile(outcome.stderrArtifact)).isTrue();

        // Every catalog required behavior must be covered by a passed assertion.
        for (String behavior : scenario.requiredBehaviors()) {
            boolean passed = outcome.assertions.stream()
                    .anyMatch(a -> behavior.equals(a.name()) && a.passed());
            assertThat(passed).as("real behavior '%s' for %s", behavior, scenarioId).isTrue();
        }
        assertThat(outcome.failureReason).as("failureReason for " + scenarioId).isBlank();

        // Per-scenario subscenario evidence: assert the M3-D-specific behaviors ran.
        if ("C05".equals(scenarioId)) {
            assertThat(outcome.assertions.stream().anyMatch(a -> a.name().equals("enhance.designated") && a.passed()))
                    .as("C05 designated loader enhanced + isolated").isTrue();
            assertThat(outcome.assertions.stream().anyMatch(a -> a.name().equals("sibling.unchanged.bytecodeHash") && a.passed()))
                    .as("C05 sibling bytecode unchanged").isTrue();
            assertThat(outcome.assertions.stream().anyMatch(a -> a.name().equals("unload.restore") && a.passed()))
                    .as("C05 precise unload/restore").isTrue();
        } else if ("C06".equals(scenarioId)) {
            for (String sub : new String[]{"jdk.proxy", "cglib.proxy", "bytebuddy.target"}) {
                assertThat(outcome.assertions.stream().anyMatch(a -> a.name().equals(sub) && a.passed()))
                        .as("C06 " + sub).isTrue();
            }
        } else if ("C07".equals(scenarioId)) {
            // Both JDK 17 and JDK 21 subscenarios must have run.
            for (String jdk : new String[]{"JDK17", "JDK21"}) {
                assertThat(outcome.assertions.stream()
                        .anyMatch(a -> a.name().equals("target.jdk." + jdk) && a.passed()))
                        .as("C07 actual target " + jdk).isTrue();
                assertThat(outcome.assertions.stream()
                        .anyMatch(a -> a.name().equals("enhance.score.through.lambda." + jdk) && a.passed()))
                        .as("C07 lambda path " + jdk).isTrue();
                assertThat(outcome.assertions.stream()
                        .anyMatch(a -> a.name().equals("enhance.compute.through.bridge." + jdk) && a.passed()))
                        .as("C07 bridge path " + jdk).isTrue();
                assertThat(outcome.assertions.stream()
                        .anyMatch(a -> a.name().equals("discover.policy.hides.synthetic.bridge." + jdk) && a.passed()))
                        .as("C07 discovery policy " + jdk).isTrue();
            }
        }
    }

    /** Resolves a target JDK home for a major version (truthful; skips if unavailable). */
    private static Path resolveJdk(int major) {
        String env = System.getenv("KAIRO_JDK" + major + "_HOME");
        if (env != null && Files.isDirectory(Path.of(env)) && jdkMajorOf(Path.of(env)) == major) {
            return Path.of(env);
        }
        if (Files.isExecutable(Path.of("/usr/libexec/java_home"))) {
            try {
                Process p = new ProcessBuilder("/usr/libexec/java_home", "-v", String.valueOf(major))
                        .redirectErrorStream(true).start();
                boolean done = p.waitFor(10_000L, TimeUnit.MILLISECONDS);
                if (done && p.exitValue() == 0) {
                    Path home = Path.of(new String(p.getInputStream().readAllBytes()).trim());
                    if (Files.isDirectory(home) && jdkMajorOf(home) == major) {
                        return home;
                    }
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (jdkMajorOf(Path.of(System.getProperty("java.home"))) == major) {
            return Path.of(System.getProperty("java.home"));
        }
        return null;
    }

    private static int jdkMajorOf(Path home) {
        Path javaBin = home.resolve("bin").resolve("java");
        if (!Files.isExecutable(javaBin)) {
            return -1;
        }
        try {
            Process p = new ProcessBuilder(javaBin.toString(), "-version").redirectErrorStream(true).start();
            boolean done = p.waitFor(10_000L, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                return -1;
            }
            String out = new String(p.getInputStream().readAllBytes());
            String quoted = out.contains("\"") ? out.substring(out.indexOf('"') + 1, out.indexOf('"', out.indexOf('"') + 1)) : "";
            if (quoted.startsWith("1.")) {
                return Integer.parseInt(quoted.substring(2, quoted.indexOf('.')));
            }
            return quoted.isEmpty() ? -1 : Integer.parseInt(quoted.split("\\.")[0]);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Finds the byte-buddy jar on the test classpath (transitive via kairo-agent-core). */
    private static Path findByteBuddyJar() {
        return findClasspathJar("byte-buddy-");
    }

    /** Finds the spring-core jar on the test classpath (repackaged CGLIB, via kairo-demo). */
    private static Path findSpringCoreJar() {
        return findClasspathJar("spring-core-");
    }

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
