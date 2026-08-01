package com.example.kairo.compatmatrix;

import java.nio.file.Path;

/**
 * Executes one M3-B plain-Java scenario against a real independent target JVM.
 *
 * <p>The {@link RealPlainJavaTargetExecutor} spawns a genuine target process
 * (premain for C01, plain JVM + external attach for C02/C09), drives the agent
 * HTTP API to enhance/invoke/update/unload, and captures the real child PID,
 * target JDK, commands and stdout/stderr artifacts. Deterministic tests
 * substitute a fake executor so the gate logic is exercised without a process.
 */
interface PlainJavaTargetExecutor {

    /**
     * Execute the scenario against a real independent target JVM using the given
     * target JDK home (already gate-checked to be one of the catalog JDKs).
     *
     * @param scenario      the C01/C02/C09 catalog scenario
     * @param env           the provisioned real-execution environment
     * @param targetJdkHome the resolved target JDK home (matches a catalog JDK)
     * @return the execution outcome (real child PID, assertions, artifacts)
     */
    PlainJavaExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env, Path targetJdkHome);
}
