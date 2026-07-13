package com.example.kairo.api.matrix;

/**
 * Outcome of running one compatibility-matrix scenario (&sect;6).
 *
 * <p>{@link #PASSED} means the automated verification succeeded on the running
 * JDK; {@link #FAILED} means it ran and did not meet the assertion (with a
 * reason); {@link #SKIPPED} means the scenario could not run on this JDK (e.g. a
 * JDK 8 scenario on a JDK 17 runner) and is deferred to the nightly matrix;
 * {@link #DOCUMENTED} means the scenario is {@link SupportLevel#LIMITED} or
 * {@link SupportLevel#EXPERIMENTAL} and verified by documentation rather than an
 * automated assertion on this runner.
 */
public enum MatrixOutcome {
    PASSED,
    FAILED,
    SKIPPED,
    DOCUMENTED
}
