package com.example.kairo.api.bytecode;

import java.util.List;
import java.util.Objects;

/**
 * Immutable metadata describing one stored bytecode snapshot. The raw bytes live
 * in the agent-side {@code BytecodeSnapshotRepository}; only this metadata and
 * hashes are exchanged with the platform by default.
 *
 * @param classIdentity    the class the snapshot belongs to
 * @param revision         the transformation revision this snapshot is anchored at
 * @param kind             INPUT, PLANNED or APPLIED
 * @param hash             content hash (e.g. SHA-256 hex), never blank
 * @param sizeBytes        length of the stored bytes, must match the stored array
 * @param capturedAtMillis wall-clock capture time, never negative
 * @param source           nullable provenance label, e.g. "jvm" or "preview"
 * @param description      nullable human-readable note
 */
public record BytecodeSnapshotMetadata(
        ClassIdentity classIdentity,
        TransformationRevision revision,
        BytecodeSnapshotKind kind,
        String hash,
        int sizeBytes,
        long capturedAtMillis,
        String source,
        String description
) {

    public BytecodeSnapshotMetadata {
        Objects.requireNonNull(classIdentity, "classIdentity");
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(kind, "kind");
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("hash must not be blank");
        }
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be >= 0");
        }
        if (capturedAtMillis < 0) {
            throw new IllegalArgumentException("capturedAtMillis must be >= 0");
        }
    }
}
