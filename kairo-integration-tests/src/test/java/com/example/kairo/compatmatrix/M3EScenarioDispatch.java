package com.example.kairo.compatmatrix;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The M3-E dispatch: gate checks + fail-closed outcome conversion for C08
 * (redefine/retransform/hot-update drift) and C10 (controlled Byte Buddy Agent
 * coexistence).
 *
 * <p>Gates (checked before any target is launched): the runner host OS/arch must match
 * the catalog (Linux x86_64), a catalog target JDK must be available on this host, the
 * agent artifacts must exist, and the scenario load mode must be PREMAIN (both are
 * premain). C10 additionally requires the resolved byte-buddy jar - a missing jar fails
 * closed rather than faking the Byte Buddy transform by a class-name marker. A formal
 * scenario that cannot run fails closed with {@code NOT_RUN}.
 *
 * <p>Outcome conversion is fail-closed and mirrors {@link PlainJavaScenarioDispatch} /
 * {@link SpringBootScenarioDispatch} / {@link M3DScenarioDispatch}: an outcome with no
 * independent child PID, a child PID equal to the runner, a timeout, or no behavior output
 * can never become {@code PASSED}. A real run with all behavior assertions passed becomes
 * {@code PASSED}; otherwise {@code FAILED} (it ran).
 *
 * <p>The M3-E fixed subscenarios (real redefine/retransform/hot-update, TARGET_DRIFTED
 * evidence, drift-not-silently-overwritten, real third-party transform, transform/behavior
 * preserved through unload) are required by name so an executor cannot claim the aggregate
 * while silently skipping one. Mock Instrumentation, in-memory-only hash comparison, a
 * skipped subscenario, RESET_ALL used to conceal drift, same-process execution or an absent
 * third-party transformation all surface here as a missing assertion -> {@code FAILED}.
 *
 * <p>Kept separate from the M3-B/M3-C/M3-D dispatches so the accepted C01-C07/C09
 * dispatch stays untouched per the M3-E work-package boundary.
 */
final class M3EScenarioDispatch {

    private M3EScenarioDispatch() {
    }

    /** Run with the real executor, resolving aux jars from system properties. */
    static CompatibilityRowRunner.DispatchResult run(CompatibilityScenario scenario, RealExecEnv env) {
        return run(scenario, env, M3EAuxJars.fromProperties(), new RealM3ETargetExecutor());
    }

    /** Run with an injected executor and explicit aux jars (real for live, fake for tests). */
    static CompatibilityRowRunner.DispatchResult run(CompatibilityScenario scenario, RealExecEnv env,
                                                     M3EAuxJars auxJars, M3ETargetExecutor executor) {
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
        // Gate 3: load mode is PREMAIN (both M3-E scenarios are premain).
        if (!isImplementedLoadMode(scenario.loadMode())) {
            return gateFailed(scenario, "load mode '" + scenario.loadMode()
                    + "' is not implemented by the M3-E executor (premain only)");
        }
        // Gate 4: a catalog target JDK is available on this host.
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
        // Gate 6 (C10 only): byte-buddy jar resolved (the controlled BB Agent's runtime dep).
        if ("C10".equals(scenario.id())) {
            String auxProblem = missingAuxJar(auxJars);
            if (auxProblem != null) {
                return gateFailed(scenario, "C10 auxiliary jar: " + auxProblem);
            }
        }

        M3EExecutionOutcome outcome;
        try {
            outcome = executor.execute(scenario, env, targetJdkHome, auxJars);
        } catch (Exception e) {
            return toDispatchResult(scenario, env, new M3EExecutionOutcome(
                    false, 0, false, "", "", "", null, null, List.of(),
                    "executor threw: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        return toDispatchResult(scenario, env, outcome);
    }

    private static boolean isImplementedLoadMode(LoadMode mode) {
        return mode == LoadMode.PREMAIN;
    }

    private static boolean requiredJdksAvailable(CompatibilityScenario scenario, RealExecEnv env) {
        for (int major : scenario.targetJdks()) {
            Path home = env.targetJdks.get(major);
            if (home == null || !Files.isDirectory(home)) {
                return false;
            }
        }
        return true;
    }

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
        // Both M3-E scenarios are premain; the attach jar is not required.
        return null;
    }

    private static String missingAuxJar(M3EAuxJars auxJars) {
        if (auxJars == null || auxJars.byteBuddyJar == null || !Files.isRegularFile(auxJars.byteBuddyJar)) {
            return "byteBuddyJar not resolved (set " + M3EAuxJars.BYTE_BUDDY_JAR_PROPERTY + ")";
        }
        return null;
    }

    private static CompatibilityRowRunner.DispatchResult gateFailed(CompatibilityScenario scenario, String reason) {
        return new CompatibilityRowRunner.DispatchResult("NOT_RUN", reason,
                List.of(), 0, false, "", "", "", "", "");
    }

    /** Converts a real execution outcome to a DispatchResult with fail-closed validation. */
    private static CompatibilityRowRunner.DispatchResult toDispatchResult(CompatibilityScenario scenario,
                                                                          RealExecEnv env,
                                                                          M3EExecutionOutcome o) {
        String stdout = o.stdoutArtifact == null ? "" : o.stdoutArtifact.toString();
        String stderr = o.stderrArtifact == null ? "" : o.stderrArtifact.toString();

        if (!o.targetStarted || o.childPid <= 0) {
            String reason = o.failureReason.isBlank()
                    ? "target did not start (no independent child PID)"
                    : "target did not start: " + o.failureReason;
            return new CompatibilityRowRunner.DispatchResult("NOT_RUN", reason,
                    List.of(), 0, false, "", "", "", "", "");
        }
        if (o.childPid == env.runnerPid || !o.independent) {
            String reason = "target pid " + o.childPid + " is not independent of runner pid "
                    + env.runnerPid + (o.failureReason.isBlank() ? "" : ": " + o.failureReason);
            return new CompatibilityRowRunner.DispatchResult("NOT_RUN", reason,
                    List.of(), 0, false, o.targetJdkVersion, o.launchCommand, o.attachCommand, stdout, stderr);
        }

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
        // M3-E fixed subscenarios whose omission must fail closed (section 10.4.5): a mock
        // Instrumentation, an in-memory-only hash comparison, a skipped redefine/retransform,
        // drift concealed by RESET_ALL, or an absent third-party transformation all surface
        // here as a missing assertion. The aggregate Chinese behavior name alone is not enough.
        for (String required : requiredSubscenarioAssertions(scenario.id())) {
            if (!covered.contains(required)) {
                evidenceErrors.add("required M3-E subscenario '" + required + "' is missing or failed");
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

    /**
     * The fixed M3-E subscenario assertion names an executor MUST cover (all passed) for
     * the row to become PASSED. They pin the real-Instrumentation / real-third-party /
     * not-silently-overwritten / preserved-through-unload invariants so the negative cases
     * in section 10.4.5 cannot slip through as a claimed pass.
     */
    private static List<String> requiredSubscenarioAssertions(String scenarioId) {
        return switch (scenarioId) {
            case "C08" -> List.of(
                    "baseline.hash",
                    "enhance.real",
                    "hotupdate.safe.reconciled",
                    "retransform.real",
                    "redefine.real",
                    "target.drifted",
                    "drift.not.silently.overwritten",
                    "unload.behavior",
                    "execution.order",
                    "instrumentation.real");
            case "C10" -> List.of(
                    "baseline.foreign.present",
                    "enhance.real",
                    "enhance.preserves.foreign",
                    "update.preserves.foreign",
                    "unload.preserves.foreign.behavior",
                    "unload.preserves.foreign.transformation",
                    "foreign.transform.real",
                    "execution.order");
            default -> List.of();
        };
    }
}
