package com.example.kairo.compatmatrix;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The M3-C Spring Boot dispatch: gate checks + fail-closed outcome conversion for
 * C03 (premain) and C04 (external attach) on a Spring Boot 3 executable-jar target.
 *
 * <p>Gates (checked before any target is launched): the runner host OS/arch must
 * match the catalog (Linux x86_64), a catalog target JDK (21) must be available, the
 * agent artifacts must exist, the Spring Boot executable jar must exist and be a
 * genuine {@code spring-boot-maven-plugin:repackage} artifact (not a plain jar), and the
 * scenario load mode must be one the Spring Boot executor implements (PREMAIN for C03,
 * EXTERNAL_ATTACH for C04). A formal scenario that cannot run fails closed with
 * {@code NOT_RUN}.
 *
 * <p>Outcome conversion is fail-closed and mirrors {@link PlainJavaScenarioDispatch}:
 * an outcome with no independent child PID, a child PID equal to the runner, a timeout,
 * an attach failure, or no behavior output can never become {@code PASSED}. The Spring
 * Boot executable-jar artifact and the real application HTTP invocation are re-checked
 * here rather than trusted from the outcome, so a path-only or self-reported claim
 * cannot pass.
 *
 * <p>Kept separate from {@code PlainJavaScenarioDispatch} so the accepted M3-B dispatch
 * stays untouched per the M3-C work-package boundary; the two share no private state and
 * duplicate only the bounded gate/conversion logic.
 */
final class SpringBootScenarioDispatch {

    /** System property carrying the Spring Boot executable-jar path (provisioned by the shell). */
    static final String EXEC_JAR_PROPERTY = "kairo.compat.artifacts.springBootExecJar";

    private SpringBootScenarioDispatch() {
    }

    /** Run with the real executor, resolving the executable jar from the environment. */
    static CompatibilityRowRunner.DispatchResult run(CompatibilityScenario scenario, RealExecEnv env) {
        return run(scenario, env, resolveExecJar(), new RealSpringBootTargetExecutor());
    }

    /** Run with an injected executor and explicit executable jar (real for live, fake for tests). */
    static CompatibilityRowRunner.DispatchResult run(CompatibilityScenario scenario, RealExecEnv env,
                                                     Path execJar, SpringBootTargetExecutor executor) {
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
        // Gate 3: load mode is one the Spring Boot executor implements.
        if (!isImplementedLoadMode(scenario.loadMode())) {
            return gateFailed(scenario, "load mode '" + scenario.loadMode()
                    + "' is not implemented by the Spring Boot executor");
        }
        // Gate 4: a catalog target JDK is available on this host.
        Path targetJdkHome = resolveTargetJdk(scenario, env);
        if (targetJdkHome == null) {
            return gateFailed(scenario, "no target JDK " + scenario.targetJdks()
                    + " available on this host (have " + env.targetJdks.keySet() + ")");
        }
        // Gate 5: agent artifacts exist.
        String missingArtifact = missingArtifact(scenario, env);
        if (missingArtifact != null) {
            return gateFailed(scenario, "agent artifact missing: " + missingArtifact);
        }
        // Gate 6: Spring Boot executable jar exists and is a real executable jar (not plain).
        String jarProblem = executableJarProblem(execJar);
        if (jarProblem != null) {
            return gateFailed(scenario, "Spring Boot executable jar: " + jarProblem);
        }

        SpringBootExecutionOutcome outcome;
        try {
            outcome = executor.execute(scenario, env, targetJdkHome, execJar);
        } catch (Exception e) {
            return toDispatchResult(scenario, env, new SpringBootExecutionOutcome(
                    false, 0, false, "", "", "", null, null, List.of(),
                    "executor threw: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        return toDispatchResult(scenario, env, outcome);
    }

    /** Resolves the executable jar from the system property (provisioned by the shell). */
    private static Path resolveExecJar() {
        String p = System.getProperty(EXEC_JAR_PROPERTY);
        return (p == null || p.isBlank()) ? null : Path.of(p);
    }

    private static boolean isImplementedLoadMode(LoadMode mode) {
        return mode == LoadMode.PREMAIN || mode == LoadMode.EXTERNAL_ATTACH;
    }

    private static Path resolveTargetJdk(CompatibilityScenario scenario, RealExecEnv env) {
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
        if (scenario.loadMode() != LoadMode.PREMAIN
                && (env.attachJar == null || !Files.isRegularFile(env.attachJar))) {
            return "attachJar";
        }
        return null;
    }

    /** Returns null if the jar is a real Spring Boot executable jar, else a reason string. */
    private static String executableJarProblem(Path execJar) {
        if (execJar == null) {
            return "not provisioned (set " + EXEC_JAR_PROPERTY + ")";
        }
        if (!Files.isRegularFile(execJar)) {
            return "not a regular file: " + execJar;
        }
        try {
            if (!SpringBootFixtureTarget.isSpringBootExecutableJar(execJar)) {
                return "not a Spring Boot executable jar (missing BOOT-INF or Spring Boot loader Main-Class): "
                        + execJar;
            }
        } catch (Exception e) {
            return "could not verify executable jar: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return null;
    }

    /** A pre-execution gate failed: C03/C04 are formal, so always NOT_RUN (fail-closed). */
    private static CompatibilityRowRunner.DispatchResult gateFailed(CompatibilityScenario scenario, String reason) {
        return new CompatibilityRowRunner.DispatchResult("NOT_RUN", reason,
                List.of(), 0, false, "", "", "", "", "");
    }

    /** Converts a real execution outcome to a DispatchResult with fail-closed validation. */
    private static CompatibilityRowRunner.DispatchResult toDispatchResult(CompatibilityScenario scenario,
                                                                          RealExecEnv env,
                                                                          SpringBootExecutionOutcome o) {
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
}
