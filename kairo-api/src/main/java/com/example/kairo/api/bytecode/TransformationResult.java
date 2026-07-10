package com.example.kairo.api.bytecode;

import java.util.List;
import java.util.Objects;

/**
 * Structured per-class outcome of one transformation attempt. This is the
 * frozen result the agent returns from {@code retransform} and records in the
 * {@code TransformationJournal}. It is a pure data type with no Byte Buddy, ASM
 * or reflection types.
 *
 * @param classIdentity     the transformed class
 * @param revision          the transformation revision this attempt was assigned
 * @param status            lifecycle status, never null
 * @param inputHash         nullable hash of the INPUT bytes
 * @param outputHash        nullable hash of the APPLIED bytes
 * @param diagnostics       immutable list of diagnostics, defensively copied
 * @param attemptedAtMillis wall-clock start time, never negative
 * @param durationMillis    elapsed time, never negative
 */
public record TransformationResult(
        ClassIdentity classIdentity,
        TransformationRevision revision,
        TransformationStatus status,
        String inputHash,
        String outputHash,
        List<TransformationDiagnostic> diagnostics,
        long attemptedAtMillis,
        long durationMillis
) {

    public TransformationResult {
        Objects.requireNonNull(classIdentity, "classIdentity");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(diagnostics, "diagnostics");
        if (attemptedAtMillis < 0) {
            throw new IllegalArgumentException("attemptedAtMillis must be >= 0");
        }
        if (durationMillis < 0) {
            throw new IllegalArgumentException("durationMillis must be >= 0");
        }
        diagnostics = List.copyOf(diagnostics);
    }
}
