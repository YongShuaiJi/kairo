package com.example.kairo.api;

import java.util.Objects;

/**
 * One entry in a canonical rule chain: the identity and ordering attributes of
 * a single rule version, stripped of script source and runtime state so it can
 * be hashed identically on the Platform and the Agent.
 *
 * <p>The canonical ordering ({@code priority DESC, createdAt ASC, ruleId ASC})
 * is applied by {@link RuleChainCanonicalizer}, not by this type's natural
 * order, so an entry list may be authored in any order and still hash to the
 * same value once canonicalized.
 *
 * @param ruleId            stable rule identifier
 * @param version           rule version (monotonic per rule)
 * @param priority          higher runs earlier within a phase
 * @param createdAtMillis   epoch-millis creation stamp (secondary order key)
 * @param scriptHash        content hash of the compiled script (content addressing)
 * @param mutexGroup        optional mutex-group label, or {@code null}
 */
public record RuleChainEntry(String ruleId, long version, int priority, long createdAtMillis,
                             String scriptHash, String mutexGroup) {

    public RuleChainEntry {
        Objects.requireNonNull(ruleId, "ruleId");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
        if (scriptHash == null) {
            scriptHash = "";
        }
        if (mutexGroup != null && mutexGroup.isBlank()) {
            mutexGroup = null;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String ruleId;
        private long version = 1L;
        private int priority;
        private long createdAtMillis;
        private String scriptHash;
        private String mutexGroup;

        private Builder() {
        }

        public Builder ruleId(String ruleId) {
            this.ruleId = ruleId;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder createdAtMillis(long createdAtMillis) {
            this.createdAtMillis = createdAtMillis;
            return this;
        }

        public Builder scriptHash(String scriptHash) {
            this.scriptHash = scriptHash;
            return this;
        }

        public Builder mutexGroup(String mutexGroup) {
            this.mutexGroup = mutexGroup;
            return this;
        }

        public RuleChainEntry build() {
            return new RuleChainEntry(ruleId, version, priority, createdAtMillis, scriptHash, mutexGroup);
        }
    }
}
