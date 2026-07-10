package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.BytecodeDecompiler;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.DecompilationResult;
import com.example.kairo.api.bytecode.DecompilationStatus;

import java.util.List;

/**
 * Default {@link BytecodeDecompiler} used when no real decompiler is registered.
 * It is explicitly unavailable: {@link #decompile(ClassIdentity, byte[])} returns an
 * honest {@link DecompilationStatus#UNAVAILABLE} result carrying a clear diagnostic,
 * so callers degrade to a bytecode view rather than presenting fabricated source.
 */
public final class UnavailableBytecodeDecompiler implements BytecodeDecompiler {

    public static final String UNAVAILABLE_MESSAGE =
            "No Java decompiler is registered with the agent. Install a BytecodeDecompiler "
                    + "implementation to obtain Java source; the structured bytecode diff remains available.";

    @Override
    public DecompilationResult decompile(ClassIdentity classIdentity, byte[] bytes) {
        return new DecompilationResult(DecompilationStatus.UNAVAILABLE, name(), null,
                List.of(UNAVAILABLE_MESSAGE), 0L);
    }

    @Override
    public String name() {
        return "unavailable";
    }
}
