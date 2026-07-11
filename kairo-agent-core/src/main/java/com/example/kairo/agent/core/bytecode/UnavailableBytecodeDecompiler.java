package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.BytecodeDecompiler;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.DecompilationResult;
import com.example.kairo.api.bytecode.DecompilationStatus;

import java.util.List;
import java.util.Objects;

/**
 * {@link BytecodeDecompiler} used when no real decompiler could be initialised. It is
 * explicitly unavailable: {@link #decompile(ClassIdentity, byte[])} returns an honest
 * {@link DecompilationStatus#UNAVAILABLE} result carrying a clear diagnostic, so callers
 * degrade to a bytecode view rather than presenting fabricated source.
 *
 * <p>The default no-arg form describes the "no decompiler registered" case. The
 * {@link #UnavailableBytecodeDecompiler(String)} form carries a specific reason - used
 * by {@link BytecodeDecompilers} when Vineflower is on the classpath in principle but
 * failed to initialise, so the diagnostic explains why the agent degraded.
 */
public final class UnavailableBytecodeDecompiler implements BytecodeDecompiler {

    public static final String UNAVAILABLE_MESSAGE =
            "No Java decompiler is registered with the agent. Install a BytecodeDecompiler "
                    + "implementation to obtain Java source; the structured bytecode diff remains available.";

    private final String message;

    /** Default unavailable decompiler with the standard "no decompiler registered" diagnostic. */
    public UnavailableBytecodeDecompiler() {
        this(UNAVAILABLE_MESSAGE);
    }

    /**
     * Unavailable decompiler that reports a specific reason (e.g. a Vineflower
     * initialisation failure). The reason must not be blank.
     */
    public UnavailableBytecodeDecompiler(String message) {
        this.message = Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    @Override
    public DecompilationResult decompile(ClassIdentity classIdentity, byte[] bytes) {
        return new DecompilationResult(DecompilationStatus.UNAVAILABLE, name(), null,
                List.of(message), 0L);
    }

    @Override
    public String name() {
        return "unavailable";
    }
}
