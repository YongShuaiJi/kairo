package com.example.kairo.compatmatrix;

import java.nio.file.Path;
import java.util.List;

/**
 * The outcome of executing one M3-D scenario (C05 ClassLoader, C06 proxy, C07
 * lambda/bridge/synthetic) against a real independent target JVM. Produced by a
 * {@link M3DTargetExecutor} and converted to a {@link CompatibilityRowRunner.DispatchResult}
 * by {@link M3DScenarioDispatch} with the same fail-closed validation as the M3-B
 * plain-Java and M3-C Spring Boot dispatches.
 *
 * <p>This mirrors {@link PlainJavaExecutionOutcome} field-for-field. It is kept as a
 * separate type so the accepted M3-B/M3-C outcome classes stay untouched per the
 * M3-D work-package boundary (the frozen C01-C04/C09 behavior must not change); the
 * fields are identical because all three carry the same independent-process evidence.
 */
final class M3DExecutionOutcome {

    /** Whether the target process was launched and reached a terminal state. */
    final boolean targetStarted;
    /** The real independent child PID (0 if the target never started). */
    final int childPid;
    /** Whether the child PID is a genuinely independent process (!= runner PID). */
    final boolean independent;
    /** The actual target JVM java.version (blank if the target never started). */
    final String targetJdkVersion;
    /** The exact {@code java} command that launched the target JVM. */
    final String launchCommand;
    /** The exact attach command; blank for C05/C06/C07 premain. */
    final String attachCommand;
    /** Path to the captured target stdout artifact. */
    final Path stdoutArtifact;
    /** Path to the captured target stderr artifact. */
    final Path stderrArtifact;
    /** Assertions derived from real target behavior (per-subscenario discovery/policy/behavior/unload). */
    final List<CompatibilityRowRunner.Assertion> assertions;
    /** Non-blank when the execution failed to complete (startup/timeout/no-behavior reason). */
    final String failureReason;

    M3DExecutionOutcome(boolean targetStarted, int childPid, boolean independent,
                        String targetJdkVersion, String launchCommand, String attachCommand,
                        Path stdoutArtifact, Path stderrArtifact,
                        List<CompatibilityRowRunner.Assertion> assertions, String failureReason) {
        this.targetStarted = targetStarted;
        this.childPid = childPid;
        this.independent = independent;
        this.targetJdkVersion = targetJdkVersion == null ? "" : targetJdkVersion;
        this.launchCommand = launchCommand == null ? "" : launchCommand;
        this.attachCommand = attachCommand == null ? "" : attachCommand;
        this.stdoutArtifact = stdoutArtifact;
        this.stderrArtifact = stderrArtifact;
        this.assertions = List.copyOf(assertions == null ? List.of() : assertions);
        this.failureReason = failureReason == null ? "" : failureReason;
    }
}
