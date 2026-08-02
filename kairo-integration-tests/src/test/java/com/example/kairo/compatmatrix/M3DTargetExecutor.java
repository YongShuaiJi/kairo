package com.example.kairo.compatmatrix;

import java.nio.file.Path;

/**
 * Executes one M3-D scenario against a real independent target JVM.
 *
 * <p>The {@link RealM3DTargetExecutor} spawns a genuine target process (premain for
 * C05/C06/C07), drives the agent HTTP API to discover targets, enhance, invoke and
 * unload, and captures the real child PID, target JDK, commands and stdout/stderr
 * artifacts. Deterministic tests substitute a fake executor so the gate logic is
 * exercised without a process.
 *
 * <p>For C07 (lambda/bridge/synthetic) the real executor launches one target for each
 * required JDK so both JDK 17 and JDK 21 are exercised, then returns one aggregated
 * outcome to the dispatch layer.
 */
interface M3DTargetExecutor {

    /**
     * Execute the scenario against a real independent target JVM using the given
     * target JDK home (already gate-checked to be one of the catalog JDKs), with the
     * resolved auxiliary jars (byte-buddy / spring-core for C06; unused for C05/C07).
     *
     * @param scenario      the C05/C06/C07 catalog scenario
     * @param env           the provisioned real-execution environment
     * @param targetJdkHome the resolved target JDK home (matches a catalog JDK)
     * @param auxJars       resolved auxiliary jars (byte-buddy, spring-core); never null
     * @return the execution outcome (real child PID, assertions, artifacts)
     */
    M3DExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env, Path targetJdkHome,
                               M3DAuxJars auxJars);
}
