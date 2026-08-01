package com.example.kairo.compatmatrix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic M3-C dispatch + gate tests for C03 (premain) and C04 (external attach)
 * on a Spring Boot 3 executable-jar target. Drives {@link SpringBootScenarioDispatch}
 * with a {@link FakeExecutor} and a controlled {@link RealExecEnv} so every gate and
 * false-positive path is exercised <strong>without</strong> spawning a real target.
 *
 * <p>The fake executor is used only to validate dispatch decision-making - it never
 * produces compatibility PASSED evidence by itself. A PASSED DispatchResult is only
 * accepted when it carries a real independent child PID, a verified Spring Boot
 * executable jar, and full behavior assertions, and the resulting row must
 * self-validate. Plain jars, wrong loader, no real invocation, missing/incomplete
 * artifacts and non-independent PIDs all fail closed.
 */
class CompatibilitySpringBootDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUILD = "0123456789abcdef0123456789abcdef01234567";
    private static final int RUNNER_PID = 9999;
    private static final int CHILD_PID = 4242;

    @TempDir
    Path tmp;

    // --------------------------------------------------------------- env helpers

    /** An env whose host matches the C03/C04 catalog (Linux x86_64 + JDK 21) with artifacts. */
    private RealExecEnv linuxX86Jdk21Env(Path bootstrap, Path core, Path attach) {
        return new RealExecEnv("Linux", "amd64", "21.0.11", RUNNER_PID, tmp, tmp,
                Map.of(21, tmp), bootstrap, bootstrap, core, attach, 10_000L, 5_000L);
    }

    private RealExecEnv env(String os, String arch, Map<Integer, Path> jdks, Path bootstrap, Path core, Path attach) {
        return new RealExecEnv(os, arch, "21.0.11", RUNNER_PID, tmp, tmp, jdks,
                bootstrap, bootstrap, core, attach, 10_000L, 5_000L);
    }

    private Path touch(String name) throws Exception {
        Path p = tmp.resolve(name);
        Files.createFile(p);
        return p;
    }

    /** A genuine-looking Spring Boot executable jar (loader Main-Class + BOOT-INF/). */
    private Path realExecJar() throws Exception {
        return writeJar("demo-exec.jar", true);
    }

    /** A plain jar (application Main-Class, no BOOT-INF/) - rejected as fake evidence. */
    private Path plainJar() throws Exception {
        return writeJar("demo-plain.jar", false);
    }

    private Path writeJar(String name, boolean springBootLoader) throws Exception {
        Path p = tmp.resolve(name);
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS,
                springBootLoader ? "org.springframework.boot.loader.launch.JarLauncher"
                        : "com.example.demo.DemoApplication");
        if (springBootLoader) {
            mf.getMainAttributes().putValue("Start-Class", "com.example.demo.DemoApplication");
        }
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(p), mf)) {
            if (springBootLoader) {
                for (String entry : List.of(
                        "org/springframework/boot/loader/launch/JarLauncher.class",
                        "BOOT-INF/classes/com/example/demo/DemoApplication.class",
                        "BOOT-INF/classes/com/example/demo/OrderService.class")) {
                    jos.putNextEntry(new ZipEntry(entry));
                    jos.closeEntry();
                }
            }
        }
        return p;
    }

    private CompatibilityCli.RunOptions opts(String scenarioId) {
        return new CompatibilityCli.RunOptions(scenarioId, tmp.resolve(scenarioId + ".json").toString(),
                BUILD, "./scripts/v1.7/run-compatibility.sh --scenario " + scenarioId, "dev", false, false);
    }

    private static CompatibilityRowRunner.Assertion ok(String name, String detail) {
        return new CompatibilityRowRunner.Assertion(name, true, detail);
    }

    private static CompatibilityRowRunner.Assertion fail(String name, String detail) {
        return new CompatibilityRowRunner.Assertion(name, false, detail);
    }

    /** An outcome where every required behavior passed (a would-be PASSED run). */
    private SpringBootExecutionOutcome allPassOutcome(CompatibilityScenario s) throws Exception {
        String targetJdk = s.targetJdks().get(0) + ".0.11";
        Path stdout = touch("target.stdout");
        Path stderr = touch("target.stderr");
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        for (String b : s.requiredBehaviors()) {
            assertions.add(ok(b, "real target behavior verified"));
        }
        assertions.add(ok("evidence.launchCommand", "/java -jar demo-exec.jar --server.port=0"));
        if (s.loadMode() != LoadMode.PREMAIN) {
            assertions.add(ok("evidence.attachCommand", "java -jar kairo-attach.jar --pid 4242"));
        }
        assertions.add(ok("evidence.stdoutArtifact", "/tmp/target.stdout"));
        assertions.add(ok("evidence.stderrArtifact", "/tmp/target.stderr"));
        return new SpringBootExecutionOutcome(true, CHILD_PID, true, targetJdk,
                "/java -jar demo-exec.jar --server.port=0",
                s.loadMode() == LoadMode.PREMAIN ? "" : "java -jar kairo-attach.jar --pid 4242",
                stdout, stderr, assertions, "");
    }

    private static final class FakeExecutor implements SpringBootTargetExecutor {
        SpringBootExecutionOutcome next;
        boolean called;

        @Override
        public SpringBootExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env,
                                                 Path targetJdkHome, Path execJar) {
            called = true;
            return next;
        }
    }

    private CompatibilityRowRunner.DispatchResult dispatchWith(CompatibilityScenario s, RealExecEnv env,
                                                              Path execJar, FakeExecutor exec) {
        return SpringBootScenarioDispatch.run(s, env, execJar, exec);
    }

    // --------------------------------------------------------------- host gates

    @Test
    void c03OnMacOsHostFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        // C03 catalog is Linux/x86_64/JDK21; an macOS arm64 host cannot run it.
        RealExecEnv env = env("Mac OS X", "aarch64", Map.of(21, Path.of("/jdk-21")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C03"), env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("Linux");
        assertThat(d.childPid()).isZero();
        assertThat(exec.called).isFalse();  // gate fails before execution
        assertThat(CompatibilityRowRunner.exitCodeForStatus(d.status()))
                .isEqualTo(CompatibilityRowRunner.EXIT_BLOCKED);
    }

    @Test
    void c04OnWrongArchFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        // C04 catalog arch is x86_64; arm64 host mismatches.
        RealExecEnv env = env("Linux", "aarch64", Map.of(21, Path.of("/jdk-21")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C04"), env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("arch");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c04WrongJdkFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        // C04 catalog JDK is 21; only JDK 17 available -> cannot run.
        RealExecEnv env = env("Linux", "x86_64", Map.of(17, Path.of("/jdk-17")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C04"), env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("JDK");
        assertThat(exec.called).isFalse();
    }

    // --------------------------------------------------------------- executable-jar gate

    @Test
    void missingExecJarFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C03"), env, null, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("executable jar", "provisioned");
        assertThat(exec.called).isFalse();
    }

    @Test
    void plainJarRejectedAsNotExecutable() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C03"), env, plainJar(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("executable jar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void nonExistentExecJarPathRejected() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C03"), env,
                tmp.resolve("does-not-exist.jar"), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("executable jar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void isSpringBootExecutableJarDistinguishesRealFromPlain() throws Exception {
        assertThat(SpringBootFixtureTarget.isSpringBootExecutableJar(realExecJar())).isTrue();
        assertThat(SpringBootFixtureTarget.isSpringBootExecutableJar(plainJar())).isFalse();
        assertThat(SpringBootFixtureTarget.isSpringBootExecutableJar(tmp.resolve("nope.jar"))).isFalse();
    }

    @Test
    void wrongSpringBootStartClassIsRejected() throws Exception {
        Path jar = realExecJar();
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().put(Attributes.Name.MAIN_CLASS,
                "org.springframework.boot.loader.launch.JarLauncher");
        mf.getMainAttributes().putValue("Start-Class", "com.example.other.OtherApplication");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jar), mf)) {
            jos.putNextEntry(new ZipEntry("org/springframework/boot/loader/launch/JarLauncher.class"));
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("BOOT-INF/classes/com/example/demo/DemoApplication.class"));
            jos.closeEntry();
            jos.putNextEntry(new ZipEntry("BOOT-INF/classes/com/example/demo/OrderService.class"));
            jos.closeEntry();
        }
        assertThat(SpringBootFixtureTarget.isSpringBootExecutableJar(jar)).isFalse();
    }

    // --------------------------------------------------------------- artifact gates

    @Test
    void c03MissingBootstrapJarFailsClosedNotRun() throws Exception {
        Path c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(null, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C03"), env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("bootstrapJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c04MissingAttachJarFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, null);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C04"), env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("attachJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void missingCoreJarFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, null, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C03"), env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("coreJar");
        assertThat(exec.called).isFalse();
    }

    // --------------------------------------------------------------- load-mode gate

    @Test
    void unsupportedLoadModeFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        // AGENTMAIN is not implemented by the Spring Boot executor (it is M3-B's C09 mode).
        CompatibilityScenario synthetic = new CompatibilityScenario("CX", CompatibilitySupportLevel.FORMAL,
                "Linux", "x86_64", List.of(21), LoadMode.AGENTMAIN, LoadMode.AGENTMAIN.raw(),
                "Spring Boot 3 executable jar", "x", List.of("x"), "M3-X",
                "synthetic unsupported-load-mode scenario");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(synthetic, env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("load mode");
        assertThat(exec.called).isFalse();
    }

    // --------------------------------------------------------------- outcome conversion

    @Test
    void missingChildPidFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new SpringBootExecutionOutcome(false, 0, false, "", "", "", null, null,
                List.of(), "Spring Boot app did not come up");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C03"), env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.childPid()).isZero();
        assertThat(d.failureReason()).contains("did not start");
    }

    @Test
    void childPidEqualsRunnerPidFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        // A child PID equal to the runner PID is not an independent process (tamper).
        exec.next = new SpringBootExecutionOutcome(true, RUNNER_PID, false, "21.0.11",
                "/java -jar demo-exec.jar", "java -jar kairo-attach.jar",
                Path.of("/tmp/o"), Path.of("/tmp/e"), List.of(ok("发布", "x")), "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C04"), env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("not independent");
    }

    @Test
    void noActualInvocationFailsClosedFailed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        // Target ran (PID > 0) but the published rule did NOT change the application HTTP
        // result (enhanced != expected): no actual invocation change -> FAILED.
        exec.next = new SpringBootExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -jar demo-exec.jar", "java -jar kairo-attach.jar --pid 4242",
                Path.of("/tmp/o"), Path.of("/tmp/e"),
                List.of(ok("attach", "attach exit=0"),
                        fail("发布", "GET /demo/score -> score=20 (expected 42, no invocation change)"),
                        ok("卸载", "restored")),
                "publish did not change application invocation result");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C04"), env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.childPid()).isEqualTo(CHILD_PID);
        assertThat(d.failureReason()).contains("invocation");
    }

    @Test
    void emptyAssertionSetCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        Path stdout = touch("target.stdout"), stderr = touch("target.stderr");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new SpringBootExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -jar demo-exec.jar", "java -jar kairo-attach.jar --pid 4242",
                stdout, stderr, List.of(), "");

        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C04"), env, realExecJar(), exec);

        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("required behavior", "no behavior assertions");
        assertThat(d.assertions()).anyMatch(x -> "harness.evidence".equals(x.name()) && !x.passed());
    }

    @Test
    void wrongTargetJdkCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        Path stdout = touch("target.stdout"), stderr = touch("target.stderr");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C03");
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        for (String behavior : s.requiredBehaviors()) {
            assertions.add(ok(behavior, "claimed pass"));
        }
        FakeExecutor exec = new FakeExecutor();
        exec.next = new SpringBootExecutionOutcome(true, CHILD_PID, true, "17.0.11",
                "/java -jar demo-exec.jar", "",
                stdout, stderr, assertions, "");

        var d = dispatchWith(s, env, realExecJar(), exec);

        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("target JDK");
    }

    @Test
    void attachFailureFailsClosedFailed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new SpringBootExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -jar demo-exec.jar", "java -jar kairo-attach.jar",
                Path.of("/tmp/o"), Path.of("/tmp/e"),
                List.of(fail("attach", "attach exit=1"), ok("evidence.attachCommand", "...")), "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C04"), env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.childPid()).isEqualTo(CHILD_PID);  // it ran, then attach failed
    }

    @Test
    void publishFailureProducesFailedRowThatValidates() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        // Real run: registration ok but publication failed (POST /rules -> 400).
        List<CompatibilityRowRunner.Assertion> assertions = List.of(
                ok("注册", "resolved OrderService via /classes"),
                fail("发布", "POST /rules -> 400 unable to resolve KairoScript"),
                fail("调用", "GET /demo/score -> score=20 (expected 42)"),
                fail("卸载", "DELETE /rules -> 400 Rule not found"),
                ok("evidence.launchCommand", "/java -javaagent:bootstrap.jar -jar demo-exec.jar"),
                ok("evidence.stdoutArtifact", "/tmp/o"),
                ok("evidence.stderrArtifact", "/tmp/e"));
        exec.next = new SpringBootExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -javaagent:bootstrap.jar -jar demo-exec.jar", "",
                Path.of("/tmp/o"), Path.of("/tmp/e"), assertions,
                "publish failed: POST /rules -> 400");
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C03");
        var d = dispatchWith(s, env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        // The resulting row must self-validate (FAILED with real PID + a failed assertion).
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C03"), s,
                "Linux", "amd64", "21.0.11", RUNNER_PID,
                "2026-08-01T00:00:00Z", "2026-08-01T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
        assertThat(row.path("targetJvm").get("pid").asInt()).isEqualTo(CHILD_PID);
        assertThat(row.path("targetJvm").get("attachCommand").asText()).isBlank();  // C03 premain
    }

    @Test
    void c03AllPassProducesPassedRowThatValidates() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C03");
        exec.next = allPassOutcome(s);
        var d = dispatchWith(s, env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("PASSED");
        assertThat(d.childPid()).isEqualTo(CHILD_PID);
        assertThat(d.childIndependent()).isTrue();
        assertThat(d.attachCommand()).isEmpty();  // C03 is premain; no attach command
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C03"), s,
                "Linux", "amd64", "21.0.11", RUNNER_PID,
                "2026-08-01T00:00:00Z", "2026-08-01T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void c04AllPassProducesPassedRowWithAttachCommand() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C04");
        exec.next = allPassOutcome(s);
        var d = dispatchWith(s, env, realExecJar(), exec);
        assertThat(d.status()).isEqualTo("PASSED");
        assertThat(d.attachCommand()).contains("kairo-attach");
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C04"), s,
                "Linux", "amd64", "21.0.11", RUNNER_PID,
                "2026-08-01T00:00:00Z", "2026-08-01T00:01:00Z", d);
        // PASSED for an attach scenario must carry attachCommand.
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
        assertThat(row.path("targetJvm").get("attachCommand").asText()).isNotBlank();
    }

    @Test
    void executorThrowFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        SpringBootTargetExecutor throwing = (scenario, e, jdk, jar) -> {
            throw new IllegalStateException("boom");
        };
        var d = SpringBootScenarioDispatch.run(CompatibilityScenarioCatalog.scenario("C03"), env,
                realExecJar(), throwing);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("executor threw");
    }
}
