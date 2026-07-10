package com.example.kairo.agent.core.bytecode;

import com.example.kairo.api.bytecode.BytecodeSnapshotKind;
import com.example.kairo.api.bytecode.ClassIdentity;
import com.example.kairo.api.bytecode.TransformationRevision;

import java.util.Objects;

/**
 * Stable key for a stored bytecode snapshot: identity + revision + kind. Holds
 * only value types, so it never strongly references a {@code Class} or
 * {@code ClassLoader}. {@link Comparable} so the repository can break ties
 * deterministically during eviction.
 */
public final class BytecodeSnapshotKey implements Comparable<BytecodeSnapshotKey> {

    private final ClassIdentity classIdentity;
    private final TransformationRevision revision;
    private final BytecodeSnapshotKind kind;

    public BytecodeSnapshotKey(ClassIdentity classIdentity,
                               TransformationRevision revision,
                               BytecodeSnapshotKind kind) {
        this.classIdentity = Objects.requireNonNull(classIdentity, "classIdentity");
        this.revision = Objects.requireNonNull(revision, "revision");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public ClassIdentity classIdentity() {
        return classIdentity;
    }

    public TransformationRevision revision() {
        return revision;
    }

    public BytecodeSnapshotKind kind() {
        return kind;
    }

    @Override
    public int compareTo(BytecodeSnapshotKey other) {
        int byClass = classIdentity.toString().compareTo(other.classIdentity.toString());
        if (byClass != 0) {
            return byClass;
        }
        int byRevision = revision.compareTo(other.revision);
        if (byRevision != 0) {
            return byRevision;
        }
        return kind.compareTo(other.kind);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BytecodeSnapshotKey that)) {
            return false;
        }
        return classIdentity.equals(that.classIdentity)
                && revision.equals(that.revision)
                && kind == that.kind;
    }

    @Override
    public int hashCode() {
        return Objects.hash(classIdentity, revision, kind);
    }

    @Override
    public String toString() {
        return classIdentity + "/" + revision + "/" + kind;
    }
}
