package com.example.kairo.api.bytecode;

/**
 * Service-provider interface for decompiling class bytes back to readable source.
 *
 * <p>V1.1 ships a default implementation backed by the official Vineflower decompiler
 * (see {@code VineflowerBytecodeDecompiler} in {@code kairo-agent-core}, shaded into the
 * {@code kairo-agent-core-modern} distribution). When Vineflower is absent or fails to
 * initialise, the agent degrades to {@code UnavailableBytecodeDecompiler}. The SPI lets
 * a later slice plug in an alternative decompiler without touching the agent runtime or
 * the platform contract.
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
