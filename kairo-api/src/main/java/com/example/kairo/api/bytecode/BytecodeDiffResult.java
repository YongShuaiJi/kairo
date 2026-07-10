package com.example.kairo.api.bytecode;

import java.util.List;
import java.util.Objects;

/**
 * Structured bytecode difference between two snapshots of the same class. Java
 * source-level diff is for readability only; the authoritative comparison is
 * the ASM-tree normalized instruction diff produced by the agent-side diff
 * service (later V1.1 slice). This DTO carries its structured output so it can
 * flow to the platform without ASM types on the wire.
 *
 * @param classIdentity    the class both snapshots belong to
 * @param fromRevision     revision of the "from" snapshot
 * @param toRevision       revision of the "to" snapshot
 * @param fromKind         kind of the "from" snapshot
 * @param toKind           kind of the "to" snapshot
 * @param fromHash         nullable hash of the "from" bytes
 * @param toHash           nullable hash of the "to" bytes
 * @param identical        true when the normalized bytes are equal
 * @param normalized       true when ASM normalization was applied before diffing
 * @param methodDiffs      immutable, defensively copied per-method differences
 * @param structuralDiffs  immutable, defensively copied non-method differences
 *                         (super class, interfaces, fields, attributes)
 * @param summary          nullable one-line summary
 */
public record BytecodeDiffResult(
        ClassIdentity classIdentity,
        TransformationRevision fromRevision,
        TransformationRevision toRevision,
        BytecodeSnapshotKind fromKind,
        BytecodeSnapshotKind toKind,
        String fromHash,
        String toHash,
        boolean identical,
        boolean normalized,
        List<MethodDiff> methodDiffs,
        List<String> structuralDiffs,
        String summary
) {

    public BytecodeDiffResult {
        Objects.requireNonNull(classIdentity, "classIdentity");
        Objects.requireNonNull(fromRevision, "fromRevision");
        Objects.requireNonNull(toRevision, "toRevision");
        Objects.requireNonNull(fromKind, "fromKind");
        Objects.requireNonNull(toKind, "toKind");
        Objects.requireNonNull(methodDiffs, "methodDiffs");
        Objects.requireNonNull(structuralDiffs, "structuralDiffs");
        methodDiffs = List.copyOf(methodDiffs);
        structuralDiffs = List.copyOf(structuralDiffs);
    }

    /**
     * Difference for a single method.
     *
     * @param methodName        method name, never blank
     * @param methodDescriptor  JVM descriptor, never blank
     * @param changeType        ADDED, REMOVED or MODIFIED
     * @param instructionDiffs  immutable instruction-level diff lines
     * @param attributeDiffs    immutable attribute-level diff lines
     *                          (exceptions, annotations, signatures)
     */
    public record MethodDiff(
            String methodName,
            String methodDescriptor,
            ChangeType changeType,
            List<String> instructionDiffs,
            List<String> attributeDiffs
    ) {

        public MethodDiff {
            if (methodName == null || methodName.isBlank()) {
                throw new IllegalArgumentException("methodName must not be blank");
            }
            if (methodDescriptor == null || methodDescriptor.isBlank()) {
                throw new IllegalArgumentException("methodDescriptor must not be blank");
            }
            Objects.requireNonNull(changeType, "changeType");
            Objects.requireNonNull(instructionDiffs, "instructionDiffs");
            Objects.requireNonNull(attributeDiffs, "attributeDiffs");
            instructionDiffs = List.copyOf(instructionDiffs);
            attributeDiffs = List.copyOf(attributeDiffs);
        }
    }

    public enum ChangeType {
        ADDED,
        REMOVED,
        MODIFIED
    }
}
