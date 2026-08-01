package com.example.kairo.compatmatrix;

import java.nio.file.Path;
import java.util.List;

/**
 * The outcome of executing one M3-B plain-Java scenario against a real
 * independent target JVM. Produced by a {@link PlainJavaTargetExecutor} and
 * converted to a {@link CompatibilityRowRunner.DispatchResult} by
 * {@link PlainJavaScenarioDispatch} with fail-closed validation.
 *
 * <p>Carries the real independent child PID, the actual target JDK version, the
 * exact launch/attach commands, the stdout/stderr artifact paths, and the
 * assertions derived from target behavior. The conversion rejects any outcome
 * that lacks an independent child PID, produced no behavior, timed out, or
 * whose attach failed - those can never become {@code PASSED}.
 */
final class PlainJavaExecutionOutcome {

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
    /** The exact attach command (C02/C09); blank for C01 premain. */
    final String attachCommand;
    /** Path to the captured target stdout artifact. */
    final Path stdoutArtifact;
    /** Path to the captured target stderr artifact. */
    final Path stderrArtifact;
    /** Assertions derived from real target behavior (attach/enhance/invoke/update/unload/shutdown). */
    final List<CompatibilityRowRunner.Assertion> assertions;
    /** Non-blank when the execution failed to complete (startup/attach/timeout/no-behavior reason). */
    final String failureReason;

    PlainJavaExecutionOutcome(boolean targetStarted, int childPid, boolean independent,
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
