package com.example.realagent;

/**
 * Real-JVM instrumentation target for {@code V16RealAgentAiLifecycleTest}. Lives outside the
 * {@code com.example.kairo.*} packages the agent's {@code IgnorePolicy} skips, so the transformer
 * weaves it. The test asserts {@link #compute(int)} actually changes on apply ({@code compute(7)}
 * 14 &rarr; 42) and is restored on revert (42 &rarr; 14), proving the AI lifecycle drives real
 * instrumentation rather than simulated acknowledgements.
 */
public class RealAgentTrialTarget {

    /** Doubles the input; {@code compute(7) == 14} until an enhancement overrides the return. */
    public int compute(int value) {
        return value * 2;
    }
}
