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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic M3-B dispatch + gate tests. Drives {@link PlainJavaScenarioDispatch}
 * with a {@link FakeExecutor} and a controlled {@link RealExecEnv} so every gate and
 * false-positive path is exercised <strong>without</strong> spawning a real target.
 *
 * <p>The fake executor is used only to validate dispatch decision-making - it never
 * produces compatibility PASSED evidence by itself. A PASSED DispatchResult is only
 * accepted when it carries a real independent child PID and full behavior assertions,
 * and the resulting row must self-validate.
 */
class CompatibilityPlainJavaDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUILD = "0123456789abcdef0123456789abcdef01234567";
    private static final int RUNNER_PID = 9999;
    private static final int CHILD_PID = 4242;

    @TempDir
    Path tmp;

    // --------------------------------------------------------------- env helpers

    /** An env whose host matches the scenario (Linux x86_64 + JDK 17) with real artifacts. */
    private RealExecEnv linuxX86Jdk17Env(Path bootstrap, Path core, Path attach) {
        // tmp is a real directory so the JDK gate passes and later gates/executors are reached.
        return new RealExecEnv("Linux", "amd64", "17.0.11", RUNNER_PID, tmp, tmp,
                Map.of(17, tmp), bootstrap, bootstrap, core, attach, 10_000L, 5_000L);
    }

    /** An env whose host is macOS arm64 + JDK 21 (the C09 host). */
    private RealExecEnv macOsArm64Jdk21Env(Path bootstrap, Path core, Path attach) {
        return new RealExecEnv("Mac OS X", "aarch64", "21.0.11", RUNNER_PID, tmp, tmp,
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
    private PlainJavaExecutionOutcome allPassOutcome(CompatibilityScenario s) throws Exception {
        String targetJdk = s.targetJdks().get(0) + ".0.11";
        Path stdout = touch("target.stdout");
        Path stderr = touch("target.stderr");
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        for (String b : s.requiredBehaviors()) {
            assertions.add(ok(b, "real target behavior verified"));
        }
        assertions.add(ok("evidence.launchCommand", "/java -cp classes PlainJavaTarget"));
        if (s.loadMode() != LoadMode.PREMAIN) {
            assertions.add(ok("evidence.attachCommand", "java -jar kairo-attach.jar --pid 4242"));
        }
        assertions.add(ok("evidence.stdoutArtifact", "/tmp/target.stdout"));
        assertions.add(ok("evidence.stderrArtifact", "/tmp/target.stderr"));
        return new PlainJavaExecutionOutcome(true, CHILD_PID, true, targetJdk,
                "/java -cp classes PlainJavaTarget",
                s.loadMode() == LoadMode.PREMAIN ? "" : "java -jar kairo-attach.jar --pid 4242",
                stdout, stderr, assertions, "");
    }

    private static final class FakeExecutor implements PlainJavaTargetExecutor {
        PlainJavaExecutionOutcome next;
        boolean called;

        @Override
        public PlainJavaExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env, Path targetJdkHome) {
            called = true;
            return next;
        }
    }

    private CompatibilityRowRunner.DispatchResult dispatchWith(CompatibilityScenario s, RealExecEnv env, FakeExecutor exec) {
        return PlainJavaScenarioDispatch.run(s, env, exec);
    }

    // --------------------------------------------------------------- host gates

    @Test
    void c01OnMacOsHostFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        // C01 catalog is Linux/x86_64/JDK17; an macOS arm64 host cannot run it.
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C01"), env, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("Linux");
        assertThat(d.childPid()).isZero();
        assertThat(exec.called).isFalse();  // gate fails before execution
        assertThat(CompatibilityRowRunner.exitCodeForStatus(d.status()))
                .isEqualTo(CompatibilityRowRunner.EXIT_BLOCKED);
    }

    @Test
    void c02OnWrongArchFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        // C02 catalog arch is x86_64; arm64 host mismatches.
        RealExecEnv env = env("Linux", "aarch64", Map.of(17, Path.of("/jdk-17")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C02"), env, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("arch");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c09OnLinuxHostReturnsExperimentalExit0() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        // C09 catalog is macOS arm64; a Linux host cannot run it -> EXPERIMENTAL (non-blocking).
        RealExecEnv env = env("Linux", "x86_64", Map.of(21, Path.of("/jdk-21")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C09"), env, exec);
        assertThat(d.status()).isEqualTo("EXPERIMENTAL");
        assertThat(d.failureReason()).contains("macOS");
        assertThat(exec.called).isFalse();
        assertThat(CompatibilityRowRunner.exitCodeForStatus(d.status()))
                .isEqualTo(CompatibilityRowRunner.EXIT_OK);
    }

    @Test
    void c09OnMacOsArm64WithoutJdk21ReturnsExperimental() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        // Host matches but JDK 21 is unavailable -> cannot truthfully execute -> EXPERIMENTAL.
        RealExecEnv env = env("Mac OS X", "aarch64", Map.of(17, Path.of("/jdk-17")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C09"), env, exec);
        assertThat(d.status()).isEqualTo("EXPERIMENTAL");
        assertThat(d.failureReason()).contains("JDK");
        assertThat(exec.called).isFalse();
    }

    // --------------------------------------------------------------- artifact gates

    @Test
    void c01MissingBootstrapJarFailsClosedNotRun() throws Exception {
        Path c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk17Env(null, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C01"), env, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("bootstrapJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c09MissingAttachJarReturnsExperimental() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, null);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C09"), env, exec);
        assertThat(d.status()).isEqualTo("EXPERIMENTAL");
        assertThat(d.failureReason()).contains("attachJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c09MissingBootstrapApiJarReturnsExperimental() throws Exception {
        Path agent = touch("bootstrap-agent.jar"), core = touch("core.jar"), attach = touch("attach.jar");
        RealExecEnv env = new RealExecEnv("Mac OS X", "aarch64", "21.0.11", RUNNER_PID,
                tmp, tmp, Map.of(21, tmp), agent, null, core, attach, 10_000L, 5_000L);
        FakeExecutor exec = new FakeExecutor();

        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C09"), env, exec);

        assertThat(d.status()).isEqualTo("EXPERIMENTAL");
        assertThat(d.failureReason()).contains("bootstrapApiJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void missingCoreJarFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk17Env(b, null, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C01"), env, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("coreJar");
        assertThat(exec.called).isFalse();
    }

    // --------------------------------------------------------------- load-mode gate

    @Test
    void unsupportedLoadModeFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        // EXTERNAL_ATTACH is not implemented by the plain-Java executor (it is M3-C's C04).
        CompatibilityScenario synthetic = new CompatibilityScenario("CX", CompatibilitySupportLevel.FORMAL,
                "Linux", "x86_64", List.of(21), LoadMode.EXTERNAL_ATTACH, LoadMode.EXTERNAL_ATTACH.raw(),
                "plain Java", "x", List.of("x"), "M3-X", "synthetic unsupported-load-mode scenario");
        RealExecEnv env = env("Linux", "x86_64", Map.of(21, Path.of("/jdk-21")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(synthetic, env, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("load mode");
        assertThat(exec.called).isFalse();
    }

    // --------------------------------------------------------------- outcome conversion

    @Test
    void missingChildPidFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new PlainJavaExecutionOutcome(false, 0, false, "", "", "", null, null,
                List.of(), "target did not print READY");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C09"), env, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.childPid()).isZero();
        assertThat(d.failureReason()).contains("did not start");
    }

    @Test
    void childPidEqualsRunnerPidFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        // A child PID equal to the runner PID is not an independent process (tamper).
        exec.next = new PlainJavaExecutionOutcome(true, RUNNER_PID, false, "21.0.11",
                "/java -cp classes PlainJavaTarget", "java -jar kairo-attach.jar",
                Path.of("/tmp/o"), Path.of("/tmp/e"), List.of(ok("增强", "x")), "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C09"), env, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("not independent");
    }

    @Test
    void compileOnlyNoBehaviorFailsClosedFailed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        // Target ran (PID > 0) but produced no behavior: invoke assertion failed.
        exec.next = new PlainJavaExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -cp classes PlainJavaTarget", "java -jar kairo-attach.jar",
                Path.of("/tmp/o"), Path.of("/tmp/e"),
                List.of(fail("增强", "INVOKE produced no RESULT (compile-only/no behavior)")),
                "compile-only: no behavior output from target");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C09"), env, exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.childPid()).isEqualTo(CHILD_PID);
        assertThat(d.failureReason()).contains("behavior");
    }

    @Test
    void emptyAssertionSetCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        Path stdout = touch("target.stdout"), stderr = touch("target.stderr");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new PlainJavaExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -cp classes PlainJavaTarget", "java -jar kairo-attach.jar --pid 4242",
                stdout, stderr, List.of(), "");

        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C09"), env, exec);

        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("required behavior", "no behavior assertions");
        assertThat(d.assertions()).anyMatch(x -> "harness.evidence".equals(x.name()) && !x.passed());
    }

    @Test
    void wrongTargetJdkCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        Path stdout = touch("target.stdout"), stderr = touch("target.stderr");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C09");
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        for (String behavior : s.requiredBehaviors()) {
            assertions.add(ok(behavior, "claimed pass"));
        }
        FakeExecutor exec = new FakeExecutor();
        exec.next = new PlainJavaExecutionOutcome(true, CHILD_PID, true, "17.0.11",
                "/java -cp classes PlainJavaTarget", "java -jar kairo-attach.jar --pid 4242",
                stdout, stderr, assertions, "");

        var d = dispatchWith(s, env, exec);

        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("target JDK");
    }

    @Test
    void timeoutFailsClosedFailed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new PlainJavaExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -cp classes PlainJavaTarget", "java -jar kairo-attach.jar",
                Path.of("/tmp/o"), Path.of("/tmp/e"),
                List.of(fail("增强", "POST /rules timed out")), "operation timed out");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C09"), env, exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("timed out");
    }

    @Test
    void attachFailureFailsClosedFailed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new PlainJavaExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -cp classes PlainJavaTarget", "java -jar kairo-attach.jar",
                Path.of("/tmp/o"), Path.of("/tmp/e"),
                List.of(fail("真实 attach", "attach exit=1"), ok("evidence.attachCommand", "...")), "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C09"), env, exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.childPid()).isEqualTo(CHILD_PID);  // it ran, then attach failed
    }

    @Test
    void enhanceFailureProducesFailedRowThatValidates() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        // Real run: attach ok but the behavior layer reports an enhancement failure.
        List<CompatibilityRowRunner.Assertion> assertions = List.of(
                ok("真实 attach", "attach exit=0; /health=UP"),
                fail("增强", "POST /rules -> 400 unable to resolve KairoScript"),
                fail("卸载", "DELETE /rules -> 400 Rule not found"),
                ok("evidence.launchCommand", "/java -cp classes PlainJavaTarget"),
                ok("evidence.attachCommand", "java -jar kairo-attach.jar --pid 4242"),
                ok("evidence.stdoutArtifact", "/tmp/o"),
                ok("evidence.stderrArtifact", "/tmp/e"));
        exec.next = new PlainJavaExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -cp classes PlainJavaTarget", "java -jar kairo-attach.jar --pid 4242",
                Path.of("/tmp/o"), Path.of("/tmp/e"), assertions,
                "enhance did not take effect: Groovy script failed to compile");
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C09");
        var d = dispatchWith(s, env, exec);
        assertThat(d.status()).isEqualTo("FAILED");
        // The resulting row must self-validate (FAILED with real PID + a failed assertion).
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C09"), s,
                "Mac OS X", "aarch64", "21.0.11", RUNNER_PID,
                "2026-08-01T00:00:00Z", "2026-08-01T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
        assertThat(row.path("targetJvm").get("pid").asInt()).isEqualTo(CHILD_PID);
        assertThat(row.path("targetJvm").get("attachCommand").asText()).isNotBlank();
    }

    @Test
    void allPassProducesPassedRowThatValidates() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C09");
        exec.next = allPassOutcome(s);
        var d = dispatchWith(s, env, exec);
        assertThat(d.status()).isEqualTo("PASSED");
        assertThat(d.childPid()).isEqualTo(CHILD_PID);
        assertThat(d.childIndependent()).isTrue();
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C09"), s,
                "Mac OS X", "aarch64", "21.0.11", RUNNER_PID,
                "2026-08-01T00:00:00Z", "2026-08-01T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void c01AllPassProducesPassedRowWithNoAttachCommand() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk17Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C01");
        exec.next = allPassOutcome(s);
        var d = dispatchWith(s, env, exec);
        assertThat(d.status()).isEqualTo("PASSED");
        assertThat(d.attachCommand()).isEmpty();  // C01 is premain; no attach command
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C01"), s,
                "Linux", "amd64", "17.0.11", RUNNER_PID,
                "2026-08-01T00:00:00Z", "2026-08-01T00:01:00Z", d);
        // PASSED for a premain scenario must not require attachCommand (blank is valid).
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void executorThrowFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = macOsArm64Jdk21Env(b, c, a);
        PlainJavaTargetExecutor throwing = (scenario, e, jdk) -> {
            throw new IllegalStateException("boom");
        };
        var d = PlainJavaScenarioDispatch.run(CompatibilityScenarioCatalog.scenario("C09"), env, throwing);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("executor threw");
    }
}
