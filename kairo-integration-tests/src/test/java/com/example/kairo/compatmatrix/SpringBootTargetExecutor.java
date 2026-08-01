package com.example.kairo.compatmatrix;

import java.nio.file.Path;

/**
 * Executes one M3-C Spring Boot scenario (C03 premain / C04 external attach) against a
 * real independent target JVM.
 *
 * <p>The {@link RealSpringBootTargetExecutor} launches a genuine Spring Boot 3
 * executable jar as an independent process, drives the real Kairo agent load path
 * (premain for C03, the repository {@code kairo-attach-cli} external attach entry for
 * C04), and proves registration/publication/invocation/unload against the agent's real
 * loopback HTTP API and the application's real HTTP endpoint. Deterministic tests
 * substitute a fake executor so the gate logic is exercised without a process.
 */
interface SpringBootTargetExecutor {

    /**
     * Execute the scenario against a real independent Spring Boot target JVM using the
     * given target JDK home and executable jar (both already gate-checked).
     *
     * @param scenario      the C03/C04 catalog scenario
     * @param env           the provisioned real-execution environment
     * @param targetJdkHome the resolved target JDK home (matches a catalog JDK)
     * @param execJar       the Spring Boot executable jar (verified a real executable jar)
     * @return the execution outcome (real child PID, assertions, artifacts)
     */
    SpringBootExecutionOutcome execute(CompatibilityScenario scenario, RealExecEnv env,
                                      Path targetJdkHome, Path execJar);
}
