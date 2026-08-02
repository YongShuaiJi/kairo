package com.example.kairo.compatmatrix;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The M3-D dispatch: gate checks + fail-closed outcome conversion for C05 (parent/child
 * same-name loaders), C06 (JDK Proxy / CGLIB / Byte Buddy) and C07 (lambda / bridge /
 * synthetic, JDK 17 and 21).
 *
 * <p>Gates (checked before any target is launched): the runner host OS/arch must match
 * the catalog (Linux x86_64), a catalog target JDK must be available on this host, the
 * agent artifacts must exist, and the scenario load mode must be PREMAIN (all three are
 * premain). C06 additionally requires the resolved byte-buddy and spring-core
 * (repackaged CGLIB) jars - a missing jar fails closed rather than faking a proxy by a
 * class-name marker. C07 additionally requires <strong>both</strong> JDK 17 and JDK 21
 * to be available, because the catalog declares both and the scenario must exercise
 * each; a missing JDK fails closed rather than faking one. A formal scenario that
 * cannot run fails closed with {@code NOT_RUN}.
 *
 * <p>Outcome conversion is fail-closed and mirrors {@link PlainJavaScenarioDispatch} /
 * {@link SpringBootScenarioDispatch}: an outcome with no independent child PID, a child
 * PID equal to the runner, a timeout, or no behavior output can never become
 * {@code PASSED}. A real run with all behavior assertions passed becomes {@code PASSED};
 * otherwise {@code FAILED} (it ran).
 *
 * <p>Kept separate from the M3-B/M3-C dispatches so the accepted C01-C04/C09 dispatch
 * stays untouched per the M3-D work-package boundary; the three share no private state
 * and duplicate only the bounded gate/conversion logic.
 */
final class M3DScenarioDispatch {

    private M3DScenarioDispatch() {
    }

    /** Run with the real executor, resolving aux jars from system properties. */
    static CompatibilityRowRunner.DispatchResult run(CompatibilityScenario scenario, RealExecEnv env) {
        return run(scenario, env, M3DAuxJars.fromProperties(), new RealM3DTargetExecutor());
    }

    /** Run with an injected executor and explicit aux jars (real for live, fake for tests). */
    static CompatibilityRowRunner.DispatchResult run(CompatibilityScenario scenario, RealExecEnv env,
                                                     M3DAuxJars auxJars, M3DTargetExecutor executor) {
        // Gate 1: host OS.
        String normOs = PlatformNormals.normalizeOs(env.hostOs);
        if (!normOs.equals(scenario.runnerOs())) {
            return gateFailed(scenario, "runner OS '" + normOs + "' (" + env.hostOs
                    + ") does not match catalog '" + scenario.runnerOs() + "'");
        }
        // Gate 2: host arch.
        String normArch = PlatformNormals.normalizeArch(env.hostArch);
        if (!normArch.equals(scenario.runnerArch())) {
            return gateFailed(scenario, "runner arch '" + normArch + "' (" + env.hostArch
                    + ") does not match catalog '" + scenario.runnerArch() + "'");
        }
        // Gate 3: load mode is PREMAIN (all M3-D scenarios are premain).
        if (!isImplementedLoadMode(scenario.loadMode())) {
            return gateFailed(scenario, "load mode '" + scenario.loadMode()
                    + "' is not implemented by the M3-D executor (premain only)");
        }
        // Gate 4: a catalog target JDK is available on this host. C07 needs BOTH 17 and 21.
        if (!requiredJdksAvailable(scenario, env)) {
            return gateFailed(scenario, "required target JDK(s) " + scenario.targetJdks()
                    + " not all available on this host (have " + env.targetJdks.keySet() + ")");
        }
        Path targetJdkHome = resolvePrimaryTargetJdk(scenario, env);
        // Gate 5: agent artifacts exist.
        String missingArtifact = missingArtifact(scenario, env);
        if (missingArtifact != null) {
            return gateFailed(scenario, "agent artifact missing: " + missingArtifact);
        }
        // Gate 6 (C06 only): byte-buddy + spring-core (repackaged CGLIB) jars resolved.
        if ("C06".equals(scenario.id())) {
            String auxProblem = missingAuxJar(auxJars);
            if (auxProblem != null) {
                return gateFailed(scenario, "C06 auxiliary jar: " + auxProblem);
            }
        }

        // Execute against a real independent target JVM (C07 runs two: JDK 17 then 21).
        M3DExecutionOutcome outcome;
        try {
            outcome = executor.execute(scenario, env, targetJdkHome, auxJars);
        } catch (Exception e) {
            return toDispatchResult(scenario, env, new M3DExecutionOutcome(
                    false, 0, false, "", "", "", null, null, List.of(),
                    "executor threw: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        return toDispatchResult(scenario, env, outcome);
    }

    private static boolean isImplementedLoadMode(LoadMode mode) {
        return mode == LoadMode.PREMAIN;
    }

    /** C07 (target JDKs [17, 21]) needs both; C05/C06 ([21]) need the single catalog JDK. */
    private static boolean requiredJdksAvailable(CompatibilityScenario scenario, RealExecEnv env) {
        for (int major : scenario.targetJdks()) {
            Path home = env.targetJdks.get(major);
            if (home == null || !Files.isDirectory(home)) {
                return false;
            }
        }
        return true;
    }

    /** The primary target JDK recorded in the row (first catalog JDK). */
    private static Path resolvePrimaryTargetJdk(CompatibilityScenario scenario, RealExecEnv env) {
        for (int major : scenario.targetJdks()) {
            Path home = env.targetJdks.get(major);
            if (home != null && Files.isDirectory(home)) {
                return home;
            }
        }
        return null;
    }

    private static String missingArtifact(CompatibilityScenario scenario, RealExecEnv env) {
        if (env.bootstrapJar == null || !Files.isRegularFile(env.bootstrapJar)) {
            return "bootstrapJar";
        }
        if (env.bootstrapApiJar == null || !Files.isRegularFile(env.bootstrapApiJar)) {
            return "bootstrapApiJar";
        }
        if (env.coreJar == null || !Files.isRegularFile(env.coreJar)) {
            return "coreJar";
        }
        // All M3-D scenarios are premain; the attach jar is not required.
        return null;
    }

    /** Returns null if both C06 aux jars exist, else a reason string. */
    private static String missingAuxJar(M3DAuxJars auxJars) {
        if (auxJars == null || auxJars.byteBuddyJar == null || !Files.isRegularFile(auxJars.byteBuddyJar)) {
            return "byteBuddyJar not resolved (set " + M3DAuxJars.BYTE_BUDDY_JAR_PROPERTY + ")";
        }
        if (auxJars.springCoreJar == null || !Files.isRegularFile(auxJars.springCoreJar)) {
            return "springCoreJar not resolved (set " + M3DAuxJars.SPRING_CORE_JAR_PROPERTY + ")";
        }
        return null;
    }

    /** A pre-execution gate failed: C05/C06/C07 are formal, so always NOT_RUN (fail-closed). */
    private static CompatibilityRowRunner.DispatchResult gateFailed(CompatibilityScenario scenario, String reason) {
        return new CompatibilityRowRunner.DispatchResult("NOT_RUN", reason,
                List.of(), 0, false, "", "", "", "", "");
    }

    /** Converts a real execution outcome to a DispatchResult with fail-closed validation. */
    private static CompatibilityRowRunner.DispatchResult toDispatchResult(CompatibilityScenario scenario,
                                                                          RealExecEnv env,
                                                                          M3DExecutionOutcome o) {
        String stdout = o.stdoutArtifact == null ? "" : o.stdoutArtifact.toString();
        String stderr = o.stderrArtifact == null ? "" : o.stderrArtifact.toString();

        // No independent child PID: the scenario did not run a real target.
        if (!o.targetStarted || o.childPid <= 0) {
            String reason = o.failureReason.isBlank()
                    ? "target did not start (no independent child PID)"
                    : "target did not start: " + o.failureReason;
            return new CompatibilityRowRunner.DispatchResult("NOT_RUN", reason,
                    List.of(), 0, false, "", "", "", "", "");
        }
        // Child PID equals the runner: not an independent process (tamper / harness bug).
        if (o.childPid == env.runnerPid || !o.independent) {
            String reason = "target pid " + o.childPid + " is not independent of runner pid "
                    + env.runnerPid + (o.failureReason.isBlank() ? "" : ": " + o.failureReason);
            return new CompatibilityRowRunner.DispatchResult("NOT_RUN", reason,
                    List.of(), 0, false, o.targetJdkVersion, o.launchCommand, o.attachCommand, stdout, stderr);
        }

        // The target ran. Re-check the executor's evidence here instead of trusting a
        // claimed all-pass outcome. This seam is also exercised with fake executors, so
        // empty/missing behavior or a path-only artifact claim must fail closed.
        List<CompatibilityRowRunner.Assertion> assertions = new ArrayList<>(o.assertions);
        List<String> evidenceErrors = new ArrayList<>();
        int targetJdkMajor = PlatformNormals.majorJdk(o.targetJdkVersion);
        if (!scenario.targetJdks().contains(targetJdkMajor)) {
            evidenceErrors.add("target JDK '" + o.targetJdkVersion
                    + "' is not one of catalog JDKs " + scenario.targetJdks());
        }
        if (o.launchCommand.isBlank()) {
            evidenceErrors.add("launch command is missing");
        }
        if (scenario.loadMode() != LoadMode.PREMAIN && o.attachCommand.isBlank()) {
            evidenceErrors.add("attach command is missing");
        }
        if (o.stdoutArtifact == null || !Files.isRegularFile(o.stdoutArtifact)) {
            evidenceErrors.add("stdout artifact is missing");
        }
        if (o.stderrArtifact == null || !Files.isRegularFile(o.stderrArtifact)) {
            evidenceErrors.add("stderr artifact is missing");
        }
        Set<String> covered = new HashSet<>();
        for (CompatibilityRowRunner.Assertion assertion : assertions) {
            if (assertion.name() != null && assertion.passed()) {
                covered.add(assertion.name());
            }
        }
        for (String required : scenario.requiredBehaviors()) {
            if (!covered.contains(required)) {
                evidenceErrors.add("required behavior '" + required + "' is missing or failed");
            }
        }
        // M3-D has fixed subscenarios whose omission must fail closed. The catalog's
        // aggregate Chinese behavior names alone are not enough: an executor could
        // otherwise claim the aggregate while silently skipping a proxy type, the
        // sibling bytecode hash, or one of C07's two actual JDK runs.
        for (String required : requiredSubscenarioAssertions(scenario.id())) {
            if (!covered.contains(required)) {
                evidenceErrors.add("required M3-D subscenario '" + required + "' is missing or failed");
            }
        }
        if (assertions.isEmpty()) {
            evidenceErrors.add("no behavior assertions were produced");
        }
        if (!evidenceErrors.isEmpty()) {
            assertions.add(new CompatibilityRowRunner.Assertion(
                    "harness.evidence", false, String.join("; ", evidenceErrors)));
        }
        if (!o.failureReason.isBlank() && assertions.stream().noneMatch(a -> !a.passed())) {
            assertions.add(new CompatibilityRowRunner.Assertion(
                    "harness.execution", false, o.failureReason));
        }

        boolean allPassed = !assertions.isEmpty()
                && assertions.stream().allMatch(CompatibilityRowRunner.Assertion::passed)
                && o.failureReason.isBlank();
        if (allPassed) {
            return new CompatibilityRowRunner.DispatchResult("PASSED", "",
                    assertions, o.childPid, true, o.targetJdkVersion,
                    o.launchCommand, o.attachCommand, stdout, stderr);
        }
        String reason = !o.failureReason.isBlank()
                ? o.failureReason
                : evidenceErrors.isEmpty()
                        ? "one or more behavior assertions failed"
                        : String.join("; ", evidenceErrors);
        return new CompatibilityRowRunner.DispatchResult("FAILED", reason,
                assertions, o.childPid, true, o.targetJdkVersion,
                o.launchCommand, o.attachCommand, stdout, stderr);
    }

    private static List<String> requiredSubscenarioAssertions(String scenarioId) {
        return switch (scenarioId) {
            case "C05" -> List.of(
                    "enhance.designated",
                    "sibling.unchanged.behavior",
                    "sibling.unchanged.bytecodeHash",
                    "designated.bytecodeHash.changed",
                    "unload.restore");
            case "C06" -> List.of(
                    "jdk.proxy.real",
                    "cglib.proxy.real",
                    "bytebuddy.target.real",
                    "proxy.target.relationships",
                    "jdk.proxy",
                    "cglib.proxy",
                    "bytebuddy.target",
                    "direct.target",
                    "unload.restore");
            case "C07" -> List.of(
                    "target.jdk.JDK17",
                    "reflect.synthetic.exists.JDK17",
                    "reflect.bridge.exists.JDK17",
                    "discover.policy.hides.synthetic.bridge.JDK17",
                    "policy.targets.concrete.JDK17",
                    "enhance.score.through.lambda.JDK17",
                    "unload.score.restore.JDK17",
                    "enhance.compute.through.bridge.JDK17",
                    "unload.compute.restore.JDK17",
                    "target.jdk.JDK21",
                    "reflect.synthetic.exists.JDK21",
                    "reflect.bridge.exists.JDK21",
                    "discover.policy.hides.synthetic.bridge.JDK21",
                    "policy.targets.concrete.JDK21",
                    "enhance.score.through.lambda.JDK21",
                    "unload.score.restore.JDK21",
                    "enhance.compute.through.bridge.JDK21",
                    "unload.compute.restore.JDK21");
            default -> List.of();
        };
    }
}
