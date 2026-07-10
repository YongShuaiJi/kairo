package com.example.kairo.api.bytecode;

import java.util.List;
import java.util.Objects;

/**
 * Frozen result of one {@link BytecodeDecompiler} attempt. Pure data type; no
 * ASM, Byte Buddy or decompiler types.
 *
 * <p>The decompiler SPI is intentionally honest: a result whose status is not
 * {@link DecompilationStatus#SUCCESS} never carries source code, so a caller can
 * never mistake an unavailable/failed attempt for a successful decompilation.
 *
 * @param status          lifecycle status, never null
 * @param decompilerName  name reported by {@link BytecodeDecompiler#name()}, never blank
 * @param sourceCode      decompiled source; null unless status is SUCCESS, in which
 *                        case it is non-blank
 * @param diagnostics     immutable human-readable diagnostics, defensively copied
 * @param durationMillis  elapsed time, never negative
 */
public record DecompilationResult(
        DecompilationStatus status,
        String decompilerName,
        String sourceCode,
        List<String> diagnostics,
        long durationMillis
) {

    public DecompilationResult {
        Objects.requireNonNull(status, "status");
        if (decompilerName == null || decompilerName.isBlank()) {
            throw new IllegalArgumentException("decompilerName must not be blank");
        }
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must be >= 0");
        }
        if (status == DecompilationStatus.SUCCESS) {
            if (sourceCode == null || sourceCode.isBlank()) {
                throw new IllegalArgumentException("sourceCode must be non-blank when status is SUCCESS");
            }
        } else if (sourceCode != null) {
            throw new IllegalArgumentException("sourceCode must be null when status is " + status);
        }
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean available() {
        return status == DecompilationStatus.SUCCESS;
    }
}
