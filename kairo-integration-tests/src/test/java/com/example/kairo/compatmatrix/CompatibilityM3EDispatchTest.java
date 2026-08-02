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
 * Deterministic M3-E dispatch + gate tests. Drives {@link M3EScenarioDispatch} with a
 * {@link FakeExecutor} and a controlled {@link RealExecEnv} so every gate and
 * false-positive path is exercised <strong>without</strong> spawning a real target.
 *
 * <p>The fake executor is used only to validate dispatch decision-making - it never
 * produces compatibility PASSED evidence by itself. A PASSED DispatchResult is only
 * accepted when it carries a real independent child PID and full behavior assertions
 * (including the fixed M3-E subscenarios that pin the real-Instrumentation /
 * real-third-party / not-silently-overwritten / preserved-through-unload invariants),
 * and the resulting row must self-validate.
 */
class CompatibilityM3EDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUILD = "0123456789abcdef0123456789abcdef01234567";
    private static final int RUNNER_PID = 9999;
    private static final int CHILD_PID = 4242;

    @TempDir
    Path tmp;

    // --------------------------------------------------------------- env helpers

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

    private M3EAuxJars aux(Path byteBuddy) {
        System.setProperty(M3EAuxJars.BYTE_BUDDY_JAR_PROPERTY, byteBuddy == null ? "" : byteBuddy.toString());
        return M3EAuxJars.fromProperties();
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

    /** An all-pass C08 outcome (safe reconcile + real redefine -> TARGET_DRIFTED, precise unload). */
    private M3EExecutionOutcome c08AllPass(CompatibilityScenario s) throws Exception {
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        assertions.add(ok("instrumentation.real", "canRedefine=true canRetransform=true inst=true"));
        assertions.add(ok("baseline.hash", "score(5)=10; baseline appliedHash=abc123"));
        assertions.add(ok("enhance.real", "POST /rules -> 201; score 10 -> 42; hash abc123 -> def456"));
        assertions.add(ok("hotupdate.safe.reconciled", "PUT /rules -> 200; score 42 -> 77 (safe reconciliation)"));
        assertions.add(ok("retransform.real", "retransformClasses=ok; score after=77"));
        assertions.add(ok("redefine.real", "redefineClasses=ok; score after=15; hash after=999000"));
        assertions.add(ok("target.drifted", "target.drifted event present: bytecode hash changed"));
        assertions.add(ok("drift.not.silently.overwritten",
                "drift event persists after hot-update=true; no RESET_ALL used=true"));
        assertions.add(ok("unload.behavior", "DELETE /rules -> 200; post-unload score=15; precise DELETE"));
        assertions.add(ok("execution.order", "baseline | enhance | hotupdate-safe | retransform | redefine | unload"));
        assertions.add(ok("成功对账或明确 TARGET_DRIFTED",
                "safe-reconciled=true; TARGET_DRIFTED-evidenced=true; not-silently-overwritten=true"));
        assertions.add(ok("evidence.launchCommand", "/java -javaagent:kairo.jar -javaagent:driftharness.jar DriftHarness"));
        assertions.add(ok("evidence.stdoutArtifact", "/tmp/target.stdout"));
        assertions.add(ok("evidence.stderrArtifact", "/tmp/target.stderr"));
        return new M3EExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -javaagent:kairo.jar -javaagent:driftharness.jar DriftHarness", "",
                touch("target.stdout"), touch("target.stderr"), assertions, "");
    }

    /** An all-pass C10 outcome (controlled BB Agent coexists; Kairo preserve transform through unload). */
    private M3EExecutionOutcome c10AllPass(CompatibilityScenario s) throws Exception {
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        assertions.add(ok("foreign.transform.real", "controlled Byte Buddy Agent installed: BB_AGENT_INSTALLED in stdout"));
        assertions.add(ok("baseline.foreign.present", "score(5)=10; tag=BB; baseline-with-BB hash=bb000"));
        assertions.add(ok("enhance.real", "POST /rules -> 201; score 10 -> 42; hash bb000 -> ka111"));
        assertions.add(ok("enhance.preserves.foreign", "tag during enhance=BB"));
        assertions.add(ok("update.preserves.foreign", "PUT /rules -> 200; score=77; tag=BB"));
        assertions.add(ok("unload.preserves.foreign.behavior", "DELETE -> 200; score restored=10; tag=BB"));
        assertions.add(ok("unload.preserves.foreign.transformation",
                "BB transform still active after unload: tag=BB (!= original \"\"); hashes baseline=bb000 enhance=ka111 update=ka222 unload=bb-rewoven"));
        assertions.add(ok("execution.order", "baseline | enhance | update | unload"));
        assertions.add(ok("Kairo 卸载不破坏对方变换",
                "bbInstalled=true; unloadBehaviorPreserved=true; unloadTransformPreserved=true"));
        assertions.add(ok("evidence.launchCommand",
                "/java -javaagent:c10-bytebuddy-agent.jar -javaagent:kairo.jar CoexistHarness"));
        assertions.add(ok("evidence.stdoutArtifact", "/tmp/target.stdout"));
        assertions.add(ok("evidence.stderrArtifact", "/tmp/target.stderr"));
        return new M3EExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -javaagent:c10-bytebuddy-agent.jar -javaagent:kairo.jar CoexistHarness", "",
                touch("target.stdout"), touch("target.stderr"), assertions, "");
    }

    private M3EExecutionOutcome allPassOutcome(CompatibilityScenario s) throws Exception {
        return switch (s.id()) {
            case "C08" -> c08AllPass(s);
            case "C10" -> c10AllPass(s);
            default -> throw new IllegalArgumentException("not an M3-E scenario: " + s.id());
        };
    }

    private static final class FakeExecutor implements M3ETargetExecutor {
        M3EExecutionOutcome next;
        boolean called;

        @Override
        public M3EExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env,
                                            Path targetJdkHome, M3EAuxJars auxJars) {
            called = true;
            return next;
        }
    }

    private CompatibilityRowRunner.DispatchResult dispatchWith(CompatibilityScenario s, RealExecEnv env,
                                                               M3EAuxJars aux, FakeExecutor exec) {
        return M3EScenarioDispatch.run(s, env, aux, exec);
    }

    // --------------------------------------------------------------- host gates

    @Test
    void c08OnMacOsHostFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = env("Mac OS X", "aarch64", Map.of(21, Path.of("/jdk-21")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("Linux");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c10OnWrongArchFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = env("Linux", "aarch64", Map.of(21, Path.of("/jdk-21")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C10"), env, aux(touch("byte-buddy.jar")), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("arch");
        assertThat(exec.called).isFalse();
    }

    // --------------------------------------------------------------- artifact / jdk gates

    @Test
    void c08MissingBootstrapJarFailsClosedNotRun() throws Exception {
        Path c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(null, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("bootstrapJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c08MissingCoreJarFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, null, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("coreJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c08MissingJdk21FailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = env("Linux", "amd64", Map.of(17, Path.of("/jdk-17")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("JDK");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c10MissingByteBuddyJarFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        // byte-buddy not resolved -> C10 aux gate fails (no faking the BB transform).
        M3EAuxJars badAux = aux(null);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C10"), env, badAux, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("byteBuddyJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c08DoesNotRequireByteBuddyJar() throws Exception {
        // C08 is pure Java (harness uses raw Instrumentation); the byte-buddy gate is C10-only.
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = c08AllPass(CompatibilityScenarioCatalog.scenario("C08"));
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(exec.called).isTrue();
        assertThat(d.status()).isEqualTo("PASSED");
    }

    // --------------------------------------------------------------- load-mode gate

    @Test
    void unsupportedLoadModeFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        CompatibilityScenario synthetic = new CompatibilityScenario("CX", CompatibilitySupportLevel.FORMAL,
                "Linux", "x86_64", List.of(21), LoadMode.EXTERNAL_ATTACH, LoadMode.EXTERNAL_ATTACH.raw(),
                "redefine", "x", List.of("x"), "M3-E", "synthetic unsupported-load-mode scenario");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(synthetic, env, M3EAuxJars.none(), exec);
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
        exec.next = new M3EExecutionOutcome(false, 0, false, "", "", "", null, null,
                List.of(), "target did not print READY");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.childPid()).isZero();
        assertThat(d.failureReason()).contains("did not start");
    }

    @Test
    void childPidEqualsRunnerPidFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3EExecutionOutcome(true, RUNNER_PID, false, "21.0.11",
                "/java -javaagent:kairo.jar DriftHarness", "", touch("o"), touch("e"),
                List.of(ok("成功对账或明确 TARGET_DRIFTED", "x")), "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("not independent");
    }

    @Test
    void emptyAssertionSetCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3EExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -javaagent:kairo.jar DriftHarness", "", touch("o"), touch("e"), List.of(), "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("required behavior", "no behavior assertions");
        assertThat(d.assertions()).anyMatch(x -> "harness.evidence".equals(x.name()) && !x.passed());
    }

    @Test
    void c08MissingTargetDriftedProofCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        M3EExecutionOutcome claimed = c08AllPass(CompatibilityScenarioCatalog.scenario("C08"));
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>(claimed.assertions);
        // Drop the TARGET_DRIFTED evidence: a redefine with no drift signal is the silent-overwrite
        // negative case and must fail closed.
        assertions.removeIf(x -> "target.drifted".equals(x.name()) || "drift.not.silently.overwritten".equals(x.name()));
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3EExecutionOutcome(true, CHILD_PID, true, claimed.targetJdkVersion,
                claimed.launchCommand, "", claimed.stdoutArtifact, claimed.stderrArtifact, assertions, "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("target.drifted");
    }

    @Test
    void c08MissingRedefineOrRetransformCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        M3EExecutionOutcome claimed = c08AllPass(CompatibilityScenarioCatalog.scenario("C08"));
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>(claimed.assertions);
        assertions.removeIf(x -> "retransform.real".equals(x.name()));
        assertions.add(fail("retransform.real", "mock Instrumentation: retransform not real"));
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3EExecutionOutcome(true, CHILD_PID, true, claimed.targetJdkVersion,
                claimed.launchCommand, "", claimed.stdoutArtifact, claimed.stderrArtifact, assertions, "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("retransform.real");
    }

    @Test
    void c08MockInstrumentationCapabilitiesCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        M3EExecutionOutcome claimed = c08AllPass(CompatibilityScenarioCatalog.scenario("C08"));
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>(claimed.assertions);
        // A mock Instrumentation reports canRedefine=false: the real-capability assertion fails.
        assertions.removeIf(x -> "instrumentation.real".equals(x.name()));
        assertions.add(fail("instrumentation.real", "canRedefine=false (mock Instrumentation)"));
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3EExecutionOutcome(true, CHILD_PID, true, claimed.targetJdkVersion,
                claimed.launchCommand, "", claimed.stdoutArtifact, claimed.stderrArtifact, assertions, "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("instrumentation.real");
    }

    @Test
    void c10MissingForeignTransformCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        M3EExecutionOutcome claimed = c10AllPass(CompatibilityScenarioCatalog.scenario("C10"));
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>(claimed.assertions);
        // An absent third-party transformation: the BB_AGENT_INSTALLED assertion fails.
        assertions.removeIf(x -> "foreign.transform.real".equals(x.name()));
        assertions.add(fail("foreign.transform.real", "BB_AGENT_INSTALLED not in stdout (no third-party transform)"));
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3EExecutionOutcome(true, CHILD_PID, true, claimed.targetJdkVersion,
                claimed.launchCommand, "", claimed.stdoutArtifact, claimed.stderrArtifact, assertions, "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C10"), env, aux(touch("byte-buddy.jar")), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("foreign.transform.real");
    }

    @Test
    void c10MissingPreservationProofCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        M3EExecutionOutcome claimed = c10AllPass(CompatibilityScenarioCatalog.scenario("C10"));
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>(claimed.assertions);
        // Drop the bytecode-preservation proof: an in-memory-only comparison cannot pass.
        assertions.removeIf(x -> "unload.preserves.foreign.transformation".equals(x.name()));
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3EExecutionOutcome(true, CHILD_PID, true, claimed.targetJdkVersion,
                claimed.launchCommand, "", claimed.stdoutArtifact, claimed.stderrArtifact, assertions, "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C10"), env, aux(touch("byte-buddy.jar")), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("unload.preserves.foreign.transformation");
    }

    @Test
    void wrongTargetJdkCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C08");
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        for (String behavior : s.requiredBehaviors()) {
            assertions.add(ok(behavior, "claimed pass"));
        }
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3EExecutionOutcome(true, CHILD_PID, true, "17.0.11",
                "/java -javaagent:kairo.jar DriftHarness", "", touch("o"), touch("e"), assertions, "");
        var d = dispatchWith(s, env, M3EAuxJars.none(), exec);
        // C08 catalog JDKs = [21]; a JDK 17 target is not in the catalog -> FAILED.
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("target JDK");
    }

    @Test
    void executorThrowFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        M3ETargetExecutor throwing = (scenario, e, jdk, aux) -> {
            throw new IllegalStateException("boom");
        };
        var d = M3EScenarioDispatch.run(CompatibilityScenarioCatalog.scenario("C08"), env,
                M3EAuxJars.none(), throwing);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("executor threw");
    }

    @Test
    void c08ResetAllUsedToConcealDriftCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        // A claimed pass that used RESET_ALL to conceal drift: the launch command carries
        // reset-all and the not-silently-overwritten assertion must be FAILED by the dispatch.
        M3EExecutionOutcome claimed = c08AllPass(CompatibilityScenarioCatalog.scenario("C08"));
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>(claimed.assertions);
        assertions.removeIf(x -> "drift.not.silently.overwritten".equals(x.name()));
        assertions.add(fail("drift.not.silently.overwritten", "RESET_ALL used to conceal drift"));
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3EExecutionOutcome(true, CHILD_PID, true, claimed.targetJdkVersion,
                "/java -javaagent:kairo.jar -Dreset-all DriftHarness", "",
                claimed.stdoutArtifact, claimed.stderrArtifact, assertions, "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C08"), env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("drift.not.silently.overwritten");
    }

    // --------------------------------------------------------------- positive paths

    @Test
    void c08AllPassProducesPassedRowThatValidates() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C08");
        exec.next = c08AllPass(s);
        var d = dispatchWith(s, env, M3EAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("PASSED");
        assertThat(d.childPid()).isEqualTo(CHILD_PID);
        assertThat(d.childIndependent()).isTrue();
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C08"), s,
                "Linux", "amd64", "21.0.11", RUNNER_PID,
                "2026-08-02T00:00:00Z", "2026-08-02T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void c10AllPassProducesPassedRowThatValidates() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C10");
        exec.next = c10AllPass(s);
        var d = dispatchWith(s, env, aux(touch("byte-buddy.jar")), exec);
        assertThat(d.status()).isEqualTo("PASSED");
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C10"), s,
                "Linux", "amd64", "21.0.11", RUNNER_PID,
                "2026-08-02T00:00:00Z", "2026-08-02T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void unprovisionedEnvFailsClosedNotRun() {
        // No real-exec env provisioned -> C08/C10 must fail closed truthfully (M3-A).
        CompatibilityScenario c08 = CompatibilityScenarioCatalog.scenario("C08");
        var d = CompatibilityRowRunner.dispatch(c08);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("not provisioned");
        CompatibilityScenario c10 = CompatibilityScenarioCatalog.scenario("C10");
        var d10 = CompatibilityRowRunner.dispatch(c10);
        assertThat(d10.status()).isEqualTo("NOT_RUN");
        assertThat(d10.failureReason()).contains("not provisioned");
    }
}
