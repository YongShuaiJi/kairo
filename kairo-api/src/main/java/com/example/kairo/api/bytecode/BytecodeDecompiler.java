package com.example.kairo.api.bytecode;

/**
 * Service-provider interface for decompiling class bytes back to readable source.
 *
 * <p>V1.1 ships only the {@code Unavailable} implementation (see
 * {@code kairo-agent-core}); no decompiler dependency is added to the agent. The
 * SPI exists so a later slice can plug in a real decompiler without touching the
 * agent runtime or the platform contract.
 *
 * <p>Implementations must be safe to call on a dedicated diagnostic executor
 * (never on a business call thread) and must enforce their own input-size,
 * concurrency and timeout limits. They must never report
 * {@link DecompilationStatus#SUCCESS} with blank or fabricated source.
 */
public interface BytecodeDecompiler {

    /**
     * Decompile the given class bytes.
     *
     * @param classIdentity identity of the class the bytes belong to (for
     *                      naming/diagnostics only; the decompiler must not
     *                      retain a reference to a live {@code Class} or
     *                      {@code ClassLoader})
     * @param bytes         class file bytes; never null
     * @return a frozen result; never null
     */
    DecompilationResult decompile(ClassIdentity classIdentity, byte[] bytes);

    /**
     * Stable short name of this implementation (e.g. {@code "unavailable"},
     * {@code "procyon"}). Used in {@link DecompilationResult#decompilerName()}
     * and diagnostics.
     */
    String name();
}
