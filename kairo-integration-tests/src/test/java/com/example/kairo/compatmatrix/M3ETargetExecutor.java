package com.example.kairo.compatmatrix;

import java.nio.file.Path;

/**
 * Executes one M3-E scenario against a real independent target JVM.
 *
 * <p>The {@link RealM3ETargetExecutor} spawns a genuine target process (premain for
 * C08/C10), drives the agent HTTP API and - for C08 - performs real
 * {@link java.lang.instrument.Instrumentation} {@code redefineClasses}/
 * {@code retransformClasses} through the harness agent, capturing the real child PID,
 * target JDK, commands, baseline/transformation hashes, execution order and
 * before/during/after behavior. Deterministic tests substitute a fake executor so the
 * gate logic is exercised without a process.
 *
 * <p>C08 proves safe reconciliation continues (hot update + retransform on a non-drifted
 * class) and that an external redefine lands precisely on TARGET_DRIFTED without silently
 * overwriting drift. C10 proves Kairo enhance/update/unload preserves the controlled Byte
 * Buddy Agent's transformation and behavior.
 */
interface M3ETargetExecutor {

    /**
     * Execute the scenario against a real independent target JVM using the given target JDK
     * home (already gate-checked to be one of the catalog JDKs), with the resolved auxiliary
     * jars (byte-buddy for C10; unused for C08).
     *
     * @param scenario      the C08/C10 catalog scenario
     * @param env           the provisioned real-execution environment
     * @param targetJdkHome the resolved target JDK home (matches a catalog JDK)
     * @param auxJars       resolved auxiliary jars (byte-buddy); never null
     * @return the execution outcome (real child PID, assertions, artifacts)
     */
    M3EExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env, Path targetJdkHome,
                                M3EAuxJars auxJars);
}
