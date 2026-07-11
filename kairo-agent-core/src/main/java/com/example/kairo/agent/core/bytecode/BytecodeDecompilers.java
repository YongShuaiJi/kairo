package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.BytecodeDecompiler;

import java.util.function.Supplier;

/**
 * Selects the {@link BytecodeDecompiler} the agent uses by default.
 *
 * <p>The default is {@link VineflowerBytecodeDecompiler}. Vineflower is a normal
 * compile-scope dependency of {@code kairo-agent-core} and is shaded into the
 * {@code kairo-agent-core-modern} distribution, so it is normally present. If, however,
 * the Vineflower classes are missing from the classpath at runtime (for example an agent
 * launched without the shaded jar) or its initialisation fails, the factory degrades to
 * an {@link UnavailableBytecodeDecompiler} carrying the reason, rather than letting the
 * agent crash. The structured bytecode diff remains usable in either case.
 */
public final class BytecodeDecompilers {

    private BytecodeDecompilers() {
    }

    /**
     * The agent's default decompiler: Vineflower when it initialises, otherwise an
     * unavailable decompiler that explains why.
     */
    public static BytecodeDecompiler defaultDecompiler() {
        return defaultDecompiler(VineflowerBytecodeDecompiler::new);
    }

    /**
     * Testable variant: constructs the Vineflower decompiler through the supplied
     * factory and falls back to unavailable if that throws a
     * {@link NoClassDefFoundError} or {@link ExceptionInInitializerError} (the shapes a
     * missing or broken dependency raises). Any other throwable is propagated.
     */
    static BytecodeDecompiler defaultDecompiler(Supplier<BytecodeDecompiler> vineflowerFactory) {
        try {
            return vineflowerFactory.get();
        } catch (LinkageError | RuntimeException e) {
            String reason = e.getClass().getSimpleName();
            String message = e.getMessage();
            if (message != null && !message.isBlank()) {
                reason += ": " + message;
            }
            return new UnavailableBytecodeDecompiler(
                    "Vineflower decompiler is not available on the agent classpath (" + reason
                            + "); the structured bytecode diff remains available.");
        }
    }
}
