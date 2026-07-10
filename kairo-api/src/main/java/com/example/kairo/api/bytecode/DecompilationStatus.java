package com.example.kairo.api.bytecode;

/**
 * Outcome status of a {@link BytecodeDecompiler} attempt.
 *
 * <ul>
 *   <li>{@link #SUCCESS} - source was produced and is non-blank;</li>
 *   <li>{@link #UNAVAILABLE} - no decompiler is configured on the agent
 *       classpath, so the request could not even be attempted;</li>
 *   <li>{@link #FAILED} - a decompiler was invoked but errored or timed out.</li>
 * </ul>
 */
public enum DecompilationStatus {
    SUCCESS,
    UNAVAILABLE,
    FAILED
}
