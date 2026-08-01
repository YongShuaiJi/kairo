package com.example.kairo.compatmatrix;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The M3-B plain-Java dispatch: gate checks + fail-closed outcome conversion
 * for C01 (premain), C02 (external attach/agentmain) and C09 (agentmain on
 * macOS arm64).
 *
 * <p>Gates (checked before any target is launched): the runner host OS/arch
 * must match the catalog, a catalog target JDK must be available on this host,
 * the agent artifacts must exist, and the scenario load mode must be one the
 * plain-Java executor implements. A formal scenario (C01/C02) that cannot run
 * fails closed with {@code NOT_RUN}; the experimental C09 fails closed with
 * {@code EXPERIMENTAL} (the documented non-blocking outcome when the host/JDK
 * cannot truthfully execute it).
 *
 * <p>Outcome conversion is fail-closed: an outcome with no independent child
 * PID, a child PID equal to the runner, a timeout, an attach failure, or no
 * behavior output can never become {@code PASSED}. A real run with all behavior
 * assertions passed becomes {@code PASSED}; otherwise {@code FAILED} (it ran).
 */
final class PlainJavaScenarioDispatch {

    private PlainJavaScenarioDispatch() {
    }

    /** Run with the real executor. */
    static CompatibilityRowRunner.DispatchResult run(CompatibilityScenario scenario, RealExecEnv env) {
        return run(scenario, env, new RealPlainJavaTargetExecutor());
    }

    /** Run with an injected executor (real for live runs, fake for deterministic tests). */
    static CompatibilityRowRunner.DispatchResult run(CompatibilityScenario scenario, RealExecEnv env,
                                                     PlainJavaTargetExecutor executor) {
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
        // Gate 3: load mode is one the plain-Java executor implements.
        if (!isImplementedLoadMode(scenario.loadMode())) {
            return gateFailed(scenario, "load mode '" + scenario.loadMode()
                    + "' is not implemented by the plain-Java executor");
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

        // Execute against a real independent target JVM.
        PlainJavaExecutionOutcome outcome;
        try {
            outcome = executor.execute(scenario, env, targetJdkHome);
        } catch (Exception e) {
            return toDispatchResult(scenario, env, new PlainJavaExecutionOutcome(
                    false, 0, false, "", "", "", null, null, List.of(),
                    "executor threw: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        return toDispatchResult(scenario, env, outcome);
    }

    private static boolean isImplementedLoadMode(LoadMode mode) {
        return mode == LoadMode.PREMAIN
                || mode == LoadMode.EXTERNAL_ATTACH_AGENTMAIN
                || mode == LoadMode.AGENTMAIN;
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

    /** A pre-execution gate failed: formal -> NOT_RUN, experimental (C09) -> EXPERIMENTAL. */
    private static CompatibilityRowRunner.DispatchResult gateFailed(CompatibilityScenario scenario, String reason) {
        if (scenario.isFormal()) {
            return new CompatibilityRowRunner.DispatchResult("NOT_RUN", reason,
                    List.of(), 0, false, "", "", "", "", "");
        }
        return new CompatibilityRowRunner.DispatchResult("EXPERIMENTAL", reason,
                List.of(), 0, false, "", "", "", "", "");
    }

    /** Converts a real execution outcome to a DispatchResult with fail-closed validation. */
    private static CompatibilityRowRunner.DispatchResult toDispatchResult(CompatibilityScenario scenario,
                                                                           RealExecEnv env,
                                                                           PlainJavaExecutionOutcome o) {
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
        // claimed all-pass outcome. This seam is also exercised with fake executors,
        // so empty/missing behavior or a path-only artifact claim must fail closed.
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
