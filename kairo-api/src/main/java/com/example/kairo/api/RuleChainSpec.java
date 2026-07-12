package com.example.kairo.api;

import java.util.List;
import java.util.Objects;

/**
 * The desired rule chain for one enhancement target, as persisted by the
 * Platform and carried in APPLY/REMOVE commands.
 *
 * <p>A spec is immutable and self-hashing: {@link #hash()} is the
 * {@link RuleChainCanonicalizer#canonicalHash canonical} SHA-256 of the target,
 * the canonically-ordered entries and the desired state. The Platform and the
 * Agent compute it the same way, so a spec's hash is the content-addressed
 * identity used for three-way reconciliation (Platform desired &harr; Agent
 * actual &harr; JVM bytecode).
 *
 * <p>{@link #revision()} is monotonic and per-target; it is <em>not</em> part
 * of the hash. Two specs with different revisions but identical content hash to
 * the same value, which is what lets a replayed command be recognized as
 * idempotent.
 *
 * @param chainId               stable chain identifier (one per agent+target)
 * @param revision              monotonic per-chain revision
 * @param target                authoritative enhancement target
 * @param entries               canonically-ordered rule entries
 * @param transformationRevision bytecode transformation revision the chain expects
 * @param desiredState          {@link ChainDesiredState#ACTIVE} or {@link ChainDesiredState#EMPTY}
 * @param hash                  canonical content hash (computed)
 */
public record RuleChainSpec(String chainId, long revision, EnhancementTarget target,
                            List<RuleChainEntry> entries, long transformationRevision,
                            ChainDesiredState desiredState, String hash) {

    public RuleChainSpec {
        Objects.requireNonNull(chainId, "chainId");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(desiredState, "desiredState");
        entries = entries == null ? List.of() : List.copyOf(RuleChainCanonicalizer.canonicalOrder(entries));
        if (desiredState == ChainDesiredState.EMPTY && !entries.isEmpty()) {
            throw new IllegalArgumentException("EMPTY desired state must carry no entries");
        }
        String computed = RuleChainCanonicalizer.hash(target, entries, desiredState);
        if (hash == null) {
            hash = computed;
        } else if (!hash.equals(computed)) {
            throw new IllegalArgumentException(
                    "RuleChainSpec hash mismatch: supplied=" + hash + " computed=" + computed);
        }
    }

    public boolean isEmpty() {
        return desiredState == ChainDesiredState.EMPTY || entries.isEmpty();
    }

    /**
     * A revision+hash token for fencing. The expected revision is what the
     * caller believes the current chain is at; the desired revision is what the
     * command wants to move to.
     */
    public RuleChainRevision revisionToken() {
        return new RuleChainRevision(revision, hash);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String chainId;
        private long revision;
        private EnhancementTarget target;
        private List<RuleChainEntry> entries = List.of();
        private long transformationRevision;
        private ChainDesiredState desiredState = ChainDesiredState.ACTIVE;
        private String hash;

        private Builder() {
        }

        public Builder chainId(String chainId) {
            this.chainId = chainId;
            return this;
        }

        public Builder revision(long revision) {
            this.revision = revision;
            return this;
        }

        public Builder target(EnhancementTarget target) {
            this.target = target;
            return this;
        }

        public Builder entries(List<RuleChainEntry> entries) {
            this.entries = entries;
            return this;
        }

        public Builder transformationRevision(long transformationRevision) {
            this.transformationRevision = transformationRevision;
            return this;
        }

        public Builder desiredState(ChainDesiredState desiredState) {
            this.desiredState = desiredState;
            return this;
        }

        /** Override the computed hash; normally left unset so the spec self-hashes. */
        public Builder hash(String hash) {
            this.hash = hash;
            return this;
        }

        public RuleChainSpec build() {
            return new RuleChainSpec(chainId, revision, target, entries, transformationRevision, desiredState, hash);
        }
    }
}
