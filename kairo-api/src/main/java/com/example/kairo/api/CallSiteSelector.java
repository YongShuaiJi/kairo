package com.example.kairo.api;

import java.util.Objects;

/**
 * Selector for a single call site inside a caller method.
 *
 * <p>A call site is one {@code invoke*} instruction. Absolute bytecode offsets
 * are not stable across recompilation, so the persisted identity is
 * <em>occurrence-index based</em>: the caller method identity (carried by the
 * owning {@link EnhancementTarget}) plus the callee signature, the invoke
 * opcode and the 0-based index of this invoke among matching invokes in the
 * caller. A surrounding-instruction {@code fingerprint} is captured at publish
 * time so a later recompilation that shifts the call site can be detected as
 * {@code TARGET_DRIFTED} rather than silently enhancing the wrong instruction.
 *
 * <p>{@code owner} uses the binary class name (dot-separated) for consistency
 * with {@link MethodSelector}; the agent converts to the internal form when
 * matching bytecode.
 */
public final class CallSiteSelector {

    private final String owner;
    private final String name;
    private final String descriptor;
    private final InvokeOpcode opcode;
    private final int occurrenceIndex;
    private final String fingerprint;

    public CallSiteSelector(String owner, String name, String descriptor,
                            InvokeOpcode opcode, int occurrenceIndex, String fingerprint) {
        this.owner = requireText(owner, "owner");
        this.name = requireText(name, "name");
        this.descriptor = requireText(descriptor, "descriptor");
        this.opcode = Objects.requireNonNull(opcode, "opcode");
        if (occurrenceIndex < 0) {
            throw new IllegalArgumentException("occurrenceIndex must be >= 0");
        }
        this.occurrenceIndex = occurrenceIndex;
        this.fingerprint = fingerprint;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String owner() {
        return owner;
    }

    public String name() {
        return name;
    }

    public String descriptor() {
        return descriptor;
    }

    public InvokeOpcode opcode() {
        return opcode;
    }

    public int occurrenceIndex() {
        return occurrenceIndex;
    }

    /** Surrounding-instruction fingerprint captured at publish time; nullable for selectors built before resolution. */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * The stable core of the identity (owner + name + descriptor + opcode +
     * occurrenceIndex). The fingerprint participates in drift detection but not
     * in core equality, so a re-resolved selector compares equal to the original
     * when the call site is still present at the same occurrence.
     */
    public boolean coreEquals(CallSiteSelector other) {
        return other != null
                && owner.equals(other.owner)
                && name.equals(other.name)
                && descriptor.equals(other.descriptor)
                && opcode == other.opcode
                && occurrenceIndex == other.occurrenceIndex;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CallSiteSelector that)) {
            return false;
        }
        return coreEquals(that) && Objects.equals(fingerprint, that.fingerprint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, name, descriptor, opcode, occurrenceIndex);
    }

    @Override
    public String toString() {
        return opcode + " " + owner + "." + name + descriptor + " #" + occurrenceIndex;
    }

    public static final class Builder {
        private String owner;
        private String name;
        private String descriptor;
        private InvokeOpcode opcode;
        private int occurrenceIndex;
        private String fingerprint;

        private Builder() {
        }

        public Builder owner(String owner) {
            this.owner = owner;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder descriptor(String descriptor) {
            this.descriptor = descriptor;
            return this;
        }

        public Builder opcode(InvokeOpcode opcode) {
            this.opcode = opcode;
            return this;
        }

        public Builder occurrenceIndex(int occurrenceIndex) {
            this.occurrenceIndex = occurrenceIndex;
            return this;
        }

        public Builder fingerprint(String fingerprint) {
            this.fingerprint = fingerprint;
            return this;
        }

        public CallSiteSelector build() {
            return new CallSiteSelector(owner, name, descriptor, opcode, occurrenceIndex, fingerprint);
        }
    }
}
