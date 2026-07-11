package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.BytecodeDecompiler;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.DecompilationResult;
import com.example.kairo.api.bytecode.DecompilationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BytecodeDecompilersTest {

    @Test
    void defaultDecompilerIsVineflowerWhenAvailable() {
        BytecodeDecompiler decompiler = BytecodeDecompilers.defaultDecompiler();

        assertThat(decompiler).isInstanceOf(VineflowerBytecodeDecompiler.class);
        assertThat(decompiler.name()).isEqualTo("vineflower");
    }

    @Test
    void fallsBackToUnavailableWhenVineflowerMissing() {
        // Simulate a missing Vineflower dependency: constructing it raises
        // NoClassDefFoundError, which the factory must convert to an unavailable
        // decompiler carrying the reason rather than crashing the agent.
        BytecodeDecompiler decompiler = BytecodeDecompilers.defaultDecompiler(() -> {
            throw new NoClassDefFoundError("org/jetbrains/java/decompiler/api/Decompiler");
        });

        assertThat(decompiler).isInstanceOf(UnavailableBytecodeDecompiler.class);
        assertThat(decompiler.name()).isEqualTo("unavailable");
        DecompilationResult result = decompiler.decompile(
                new ClassIdentity("x.Y", "loader"), new byte[]{1});
        assertThat(result.status()).isEqualTo(DecompilationStatus.UNAVAILABLE);
        assertThat(result.sourceCode()).isNull();
        assertThat(result.diagnostics()).anyMatch(s -> s.contains("Vineflower") && s.contains("not available"));
    }

    @Test
    void fallsBackToUnavailableOnInitializerFailure() {
        BytecodeDecompiler decompiler = BytecodeDecompilers.defaultDecompiler(() -> {
            throw new ExceptionInInitializerError("vineflower broke");
        });

        assertThat(decompiler).isInstanceOf(UnavailableBytecodeDecompiler.class);
        DecompilationResult result = decompiler.decompile(
                new ClassIdentity("x.Y", "loader"), new byte[]{1});
        assertThat(result.status()).isEqualTo(DecompilationStatus.UNAVAILABLE);
        assertThat(result.diagnostics()).anyMatch(s -> s.contains("ExceptionInInitializerError"));
    }

    @Test
    void fallsBackWhenConstructionFailsAtRuntime() {
        BytecodeDecompiler decompiler = BytecodeDecompilers.defaultDecompiler(() -> {
            throw new IllegalStateException("unexpected");
        });
        assertThat(decompiler).isInstanceOf(UnavailableBytecodeDecompiler.class);
    }
}
