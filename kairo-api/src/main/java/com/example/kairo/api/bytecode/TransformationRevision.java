package com.example.kairo.api.bytecode;

/**
 * Monotonic revision number for the transformation state of one target class
 * inside an agent. Revision {@code 0} ({@link #INITIAL}) means the class has not
 * been transformed yet; the journal assigns {@code 1, 2, 3, ...} per class.
 *
 * <p>Revisions are assigned per {@link ClassIdentity} and are monotonic for the
 * lifetime of an agent; clearing bounded history never resets the counter.
 */
public final class TransformationRevision implements Comparable<TransformationRevision> {

    public static final TransformationRevision INITIAL = new TransformationRevision(0L);

    private final long value;

    public TransformationRevision(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("revision must be >= 0: " + value);
        }
        this.value = value;
    }

    public static TransformationRevision of(long value) {
        return new TransformationRevision(value);
    }

    public long value() {
        return value;
    }

    public boolean isInitial() {
        return value == 0L;
    }

    public TransformationRevision next() {
        if (value == Long.MAX_VALUE) {
            throw new IllegalStateException(
                    "revision overflow: reached Long.MAX_VALUE and cannot advance further");
        }
        return new TransformationRevision(value + 1L);
    }

    @Override
    public int compareTo(TransformationRevision other) {
        return Long.compare(value, other.value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TransformationRevision that)) {
            return false;
        }
        return value == that.value;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(value);
    }

    @Override
    public String toString() {
        return "r" + value;
    }
}
