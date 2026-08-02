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
 * Deterministic M3-D dispatch + gate tests. Drives {@link M3DScenarioDispatch} with a
 * {@link FakeExecutor} and a controlled {@link RealExecEnv} so every gate and
 * false-positive path is exercised <strong>without</strong> spawning a real target.
 *
 * <p>The fake executor is used only to validate dispatch decision-making - it never
 * produces compatibility PASSED evidence by itself. A PASSED DispatchResult is only
 * accepted when it carries a real independent child PID and full behavior assertions,
 * and the resulting row must self-validate.
 */
class CompatibilityM3DDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BUILD = "0123456789abcdef0123456789abcdef01234567";
    private static final int RUNNER_PID = 9999;
    private static final int CHILD_PID = 4242;

    @TempDir
    Path tmp;

    // --------------------------------------------------------------- env helpers

    /** Linux x86_64 + JDK 21 env (C05/C06 catalog host). */
    private RealExecEnv linuxX86Jdk21Env(Path bootstrap, Path core, Path attach) {
        return new RealExecEnv("Linux", "amd64", "21.0.11", RUNNER_PID, tmp, tmp,
                Map.of(21, tmp), bootstrap, bootstrap, core, attach, 10_000L, 5_000L);
    }

    /** Linux x86_64 + JDK 17 and 21 env (C07 needs both). */
    private RealExecEnv linuxX86Jdk17And21Env(Path bootstrap, Path core, Path attach, Path jdk17, Path jdk21) {
        return new RealExecEnv("Linux", "amd64", "21.0.11", RUNNER_PID, tmp, tmp,
                Map.of(17, jdk17, 21, jdk21), bootstrap, bootstrap, core, attach, 10_000L, 5_000L);
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

    private M3DAuxJars aux(Path byteBuddy, Path springCore) {
        // The aux-jar record is constructed reflectively-inaccessible; use fromProperties
        // by setting system properties, OR use the public none()/fromProperties. For tests
        // we resolve aux via fromProperties with sysprops set per-test.
        System.setProperty(M3DAuxJars.BYTE_BUDDY_JAR_PROPERTY, byteBuddy == null ? "" : byteBuddy.toString());
        System.setProperty(M3DAuxJars.SPRING_CORE_JAR_PROPERTY, springCore == null ? "" : springCore.toString());
        return M3DAuxJars.fromProperties();
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

    /** An all-pass C05 outcome (designated loader enhanced, sibling unchanged). */
    private M3DExecutionOutcome c05AllPass(CompatibilityScenario s) throws Exception {
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        assertions.add(ok("发现.A", "classId=A (designated)"));
        assertions.add(ok("发现.B", "classId=B (sibling)"));
        assertions.add(ok("baseline.both", "A=10 B=10"));
        assertions.add(ok("enhance.designated", "POST /rules -> 201; A=42 B=10 (exactly one enhanced)"));
        assertions.add(ok("sibling.unchanged.behavior", "while designated enhanced: A=42 B=10 (sibling stays 10)"));
        assertions.add(ok("sibling.unchanged.bytecodeHash", "sibling applied hash unchanged"));
        assertions.add(ok("designated.bytecodeHash.changed", "designated applied hash changed"));
        assertions.add(ok("unload.restore", "DELETE -> 200; A=10 B=10"));
        assertions.add(ok("只增强指定 loader", "designated enhanced+isolated+restored; sibling unchanged"));
        assertions.add(ok("evidence.launchCommand", "/java -cp classes LoaderTargetHarness"));
        assertions.add(ok("evidence.stdoutArtifact", "/tmp/target.stdout"));
        assertions.add(ok("evidence.stderrArtifact", "/tmp/target.stderr"));
        return new M3DExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -cp classes LoaderTargetHarness", "", touch("target.stdout"), touch("target.stderr"),
                assertions, "");
    }

    /** An all-pass C06 outcome (three proxies enhanced + restored). */
    private M3DExecutionOutcome c06AllPass(CompatibilityScenario s) throws Exception {
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        assertions.add(ok("jdk.proxy.real", "Proxy.isProxyClass=true"));
        assertions.add(ok("cglib.proxy.real", "ProxyTarget$$SpringCGLIB$$0"));
        assertions.add(ok("bytebuddy.target.real", "ProxyTarget$ByteBuddy$abc"));
        assertions.add(ok("proxy.target.relationships", "all proxy paths resolve to ProxyTarget"));
        assertions.add(ok("目标解析", "classId=ProxyTarget"));
        assertions.add(ok("baseline.proxies", "jdk=20 cglib=20 bytebuddy=20 direct=20"));
        assertions.add(ok("jdk.proxy", "POST -> 201; JDK proxy 20 -> 42"));
        assertions.add(ok("cglib.proxy", "CGLIB proxy 20 -> 42 (genuine Enhancer)"));
        assertions.add(ok("bytebuddy.target", "Byte Buddy subclass 20 -> 42 (genuine runtime subclass)"));
        assertions.add(ok("direct.target", "resolved target 20 -> 42"));
        assertions.add(ok("unload.restore", "DELETE -> 200; jdk=20 cglib=20 bytebuddy=20"));
        assertions.add(ok("目标解析与精确卸载", "resolved; jdk; cglib; bytebuddy; unload"));
        assertions.add(ok("evidence.launchCommand", "/java -cp classes:bb.jar:sc.jar ProxyTargetHarness"));
        assertions.add(ok("evidence.stdoutArtifact", "/tmp/target.stdout"));
        assertions.add(ok("evidence.stderrArtifact", "/tmp/target.stderr"));
        return new M3DExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -cp classes:bb.jar:sc.jar ProxyTargetHarness", "",
                touch("target.stdout"), touch("target.stderr"), assertions, "");
    }

    /** An all-pass C07 outcome (both JDK 17 and 21 subscenarios passed). */
    private M3DExecutionOutcome c07AllPass(CompatibilityScenario s) throws Exception {
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        for (String jdk : new String[]{"JDK17", "JDK21"}) {
            assertions.add(ok("target.jdk." + jdk, "target reported expected " + jdk));
            assertions.add(ok("reflect.synthetic.exists." + jdk, "lambda synthetic method exists"));
            assertions.add(ok("reflect.bridge.exists." + jdk, "IntNode bridge method exists"));
            assertions.add(ok("discover.classes." + jdk, "LambdaBridgeTarget=true IntNode=true"));
            assertions.add(ok("discover.policy.hides.synthetic.bridge." + jdk,
                    "score listed; lambda$ not listed; compute(Integer) listed; compute(Number) not listed"));
            assertions.add(ok("policy.targets.concrete." + jdk, "score resolved; compute(Integer) resolved"));
            assertions.add(ok("baseline.paths." + jdk, "lambda=10 score=10 bridge=105 concrete=105"));
            assertions.add(ok("enhance.score.through.lambda." + jdk, "POST -> 201; lambda 10 -> 42"));
            assertions.add(ok("unload.score.restore." + jdk, "DELETE -> 200; lambda -> 10"));
            assertions.add(ok("enhance.compute.through.bridge." + jdk, "POST -> 201; bridge 105 -> 200"));
            assertions.add(ok("unload.compute.restore." + jdk, "DELETE -> 200; bridge -> 105"));
        }
        // Bare aggregate required-behavior assertions (both JDKs).
        assertions.add(ok("发现", "discovery policy on JDK 17 and 21"));
        assertions.add(ok("策略", "stable concrete method targeted on JDK 17 and 21"));
        assertions.add(ok("实际行为", "enhancement through lambda+bridge + unload on JDK 17 and 21"));
        assertions.add(ok("evidence.launchCommand", "/java -cp classes LambdaBridgeTarget"));
        assertions.add(ok("evidence.stdoutArtifact", "/tmp/target.stdout"));
        assertions.add(ok("evidence.stderrArtifact", "/tmp/target.stderr"));
        return new M3DExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -cp classes LambdaBridgeTarget", "", touch("target.stdout"), touch("target.stderr"),
                assertions, "");
    }

    private M3DExecutionOutcome allPassOutcome(CompatibilityScenario s) throws Exception {
        return switch (s.id()) {
            case "C05" -> c05AllPass(s);
            case "C06" -> c06AllPass(s);
            case "C07" -> c07AllPass(s);
            default -> throw new IllegalArgumentException("not an M3-D scenario: " + s.id());
        };
    }

    private static final class FakeExecutor implements M3DTargetExecutor {
        M3DExecutionOutcome next;
        boolean called;

        @Override
        public M3DExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env,
                                            Path targetJdkHome, M3DAuxJars auxJars) {
            called = true;
            return next;
        }
    }

    private CompatibilityRowRunner.DispatchResult dispatchWith(CompatibilityScenario s, RealExecEnv env,
                                                               M3DAuxJars aux, FakeExecutor exec) {
        return M3DScenarioDispatch.run(s, env, aux, exec);
    }

    // --------------------------------------------------------------- host gates

    @Test
    void c05OnMacOsHostFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = env("Mac OS X", "aarch64", Map.of(21, Path.of("/jdk-21")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C05"), env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("Linux");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c06OnWrongArchFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = env("Linux", "aarch64", Map.of(21, Path.of("/jdk-21")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C06"), env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("arch");
        assertThat(exec.called).isFalse();
    }

    // --------------------------------------------------------------- artifact / jdk gates

    @Test
    void c05MissingBootstrapJarFailsClosedNotRun() throws Exception {
        Path c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(null, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C05"), env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("bootstrapJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c05MissingCoreJarFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, null, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C05"), env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("coreJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c05MissingJdk21FailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = env("Linux", "amd64", Map.of(17, Path.of("/jdk-17")), b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C05"), env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("JDK");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c06MissingByteBuddyJarFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        // spring-core present but byte-buddy missing -> C06 aux gate fails.
        M3DAuxJars aux = aux(null, touch("spring-core.jar"));
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C06"), env, aux, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("byteBuddyJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c06MissingSpringCoreJarFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        M3DAuxJars aux = aux(touch("byte-buddy.jar"), null);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C06"), env, aux, exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("springCoreJar");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c07MissingJdk17FailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        // C07 needs both 17 and 21; only 21 present -> gate fails.
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C07"), env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("JDK");
        assertThat(exec.called).isFalse();
    }

    @Test
    void c07WithBothJdksPassesGate() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk17And21Env(b, c, a, tmp, tmp);
        FakeExecutor exec = new FakeExecutor();
        exec.next = c07AllPass(CompatibilityScenarioCatalog.scenario("C07"));
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C07"), env, M3DAuxJars.none(), exec);
        assertThat(exec.called).isTrue();
        assertThat(d.status()).isEqualTo("PASSED");
    }

    // --------------------------------------------------------------- load-mode gate

    @Test
    void unsupportedLoadModeFailsClosed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        CompatibilityScenario synthetic = new CompatibilityScenario("CX", CompatibilitySupportLevel.FORMAL,
                "Linux", "x86_64", List.of(21), LoadMode.EXTERNAL_ATTACH, LoadMode.EXTERNAL_ATTACH.raw(),
                "plain Java", "x", List.of("x"), "M3-X", "synthetic unsupported-load-mode scenario");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        var d = dispatchWith(synthetic, env, M3DAuxJars.none(), exec);
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
        exec.next = new M3DExecutionOutcome(false, 0, false, "", "", "", null, null,
                List.of(), "target did not print READY");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C05"), env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.childPid()).isZero();
        assertThat(d.failureReason()).contains("did not start");
    }

    @Test
    void childPidEqualsRunnerPidFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3DExecutionOutcome(true, RUNNER_PID, false, "21.0.11",
                "/java -cp classes LoaderTargetHarness", "", touch("o"), touch("e"),
                List.of(ok("只增强指定 loader", "x")), "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C05"), env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("not independent");
    }

    @Test
    void emptyAssertionSetCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3DExecutionOutcome(true, CHILD_PID, true, "21.0.11",
                "/java -cp classes LoaderTargetHarness", "", touch("o"), touch("e"), List.of(), "");
        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C05"), env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("required behavior", "no behavior assertions");
        assertThat(d.assertions()).anyMatch(x -> "harness.evidence".equals(x.name()) && !x.passed());
    }

    @Test
    void c05MissingBytecodeHashProofCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        M3DExecutionOutcome claimed = c05AllPass(CompatibilityScenarioCatalog.scenario("C05"));
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>(claimed.assertions);
        assertions.removeIf(x -> "sibling.unchanged.bytecodeHash".equals(x.name()));
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3DExecutionOutcome(true, CHILD_PID, true, claimed.targetJdkVersion,
                claimed.launchCommand, "", claimed.stdoutArtifact, claimed.stderrArtifact, assertions, "");

        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C05"), env, M3DAuxJars.none(), exec);

        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("sibling.unchanged.bytecodeHash");
    }

    @Test
    void c06MissingOneProxyTypeCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        Path bb = touch("byte-buddy.jar"), sc = touch("spring-core.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        M3DExecutionOutcome claimed = c06AllPass(CompatibilityScenarioCatalog.scenario("C06"));
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>(claimed.assertions);
        assertions.removeIf(x -> "cglib.proxy".equals(x.name()));
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3DExecutionOutcome(true, CHILD_PID, true, claimed.targetJdkVersion,
                claimed.launchCommand, "", claimed.stdoutArtifact, claimed.stderrArtifact, assertions, "");

        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C06"), env, aux(bb, sc), exec);

        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("cglib.proxy");
    }

    @Test
    void c07MissingJdk17SubscenarioCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk17And21Env(b, c, a, tmp, tmp);
        M3DExecutionOutcome claimed = c07AllPass(CompatibilityScenarioCatalog.scenario("C07"));
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>(claimed.assertions);
        assertions.removeIf(x -> x.name().endsWith(".JDK17"));
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3DExecutionOutcome(true, CHILD_PID, true, claimed.targetJdkVersion,
                claimed.launchCommand, "", claimed.stdoutArtifact, claimed.stderrArtifact, assertions, "");

        var d = dispatchWith(CompatibilityScenarioCatalog.scenario("C07"), env, M3DAuxJars.none(), exec);

        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("JDK17");
    }

    @Test
    void wrongTargetJdkCannotBecomePassed() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C05");
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>();
        for (String behavior : s.requiredBehaviors()) {
            assertions.add(ok(behavior, "claimed pass"));
        }
        FakeExecutor exec = new FakeExecutor();
        exec.next = new M3DExecutionOutcome(true, CHILD_PID, true, "17.0.11",
                "/java -cp classes LoaderTargetHarness", "", touch("o"), touch("e"), assertions, "");
        var d = dispatchWith(s, env, M3DAuxJars.none(), exec);
        // C05 catalog JDKs = [21]; a JDK 17 target is not in the catalog -> FAILED.
        assertThat(d.status()).isEqualTo("FAILED");
        assertThat(d.failureReason()).contains("target JDK");
    }

    @Test
    void executorThrowFailsClosedNotRun() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        M3DTargetExecutor throwing = (scenario, e, jdk, aux) -> {
            throw new IllegalStateException("boom");
        };
        var d = M3DScenarioDispatch.run(CompatibilityScenarioCatalog.scenario("C05"), env,
                M3DAuxJars.none(), throwing);
        assertThat(d.status()).isEqualTo("NOT_RUN");
        assertThat(d.failureReason()).contains("executor threw");
    }

    // --------------------------------------------------------------- positive paths

    @Test
    void c05AllPassProducesPassedRowThatValidates() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C05");
        exec.next = c05AllPass(s);
        var d = dispatchWith(s, env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("PASSED");
        assertThat(d.childPid()).isEqualTo(CHILD_PID);
        assertThat(d.childIndependent()).isTrue();
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C05"), s,
                "Linux", "amd64", "21.0.11", RUNNER_PID,
                "2026-08-02T00:00:00Z", "2026-08-02T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void c06AllPassProducesPassedRowThatValidates() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk21Env(b, c, a);
        FakeExecutor exec = new FakeExecutor();
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C06");
        exec.next = c06AllPass(s);
        var d = dispatchWith(s, env, aux(touch("byte-buddy.jar"), touch("spring-core.jar")), exec);
        assertThat(d.status()).isEqualTo("PASSED");
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C06"), s,
                "Linux", "amd64", "21.0.11", RUNNER_PID,
                "2026-08-02T00:00:00Z", "2026-08-02T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }

    @Test
    void c07AllPassProducesPassedRowThatValidates() throws Exception {
        Path b = touch("bootstrap.jar"), c = touch("core.jar"), a = touch("attach.jar");
        RealExecEnv env = linuxX86Jdk17And21Env(b, c, a, tmp, tmp);
        FakeExecutor exec = new FakeExecutor();
        CompatibilityScenario s = CompatibilityScenarioCatalog.scenario("C07");
        exec.next = c07AllPass(s);
        var d = dispatchWith(s, env, M3DAuxJars.none(), exec);
        assertThat(d.status()).isEqualTo("PASSED");
        // C07 row records the primary (JDK 21) target JVM; 21 is in catalog JDKs [17, 21].
        JsonNode row = CompatibilityRowRunner.buildRowForResult(opts("C07"), s,
                "Linux", "amd64", "21.0.11", RUNNER_PID,
                "2026-08-02T00:00:00Z", "2026-08-02T00:01:00Z", d);
        assertThat(new CompatibilityRowValidator().validate(row)).isEmpty();
    }
}
