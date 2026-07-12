package com.example.kairo.api;

import java.util.List;
import java.util.Objects;

/**
 * A fenced APPLY/REMOVE command from the Platform to an Agent (&sect;3.3).
 *
 * <p>Every command carries a command id and idempotency key, the expected
 * current revision of the target chain (fencing token), the desired chain spec
 * (which itself carries the desired revision and content hash), the full rule
 * content (scripts + targets) needed to compile, the target identity, and a
 * deadline. The Agent accepts the transition only when the expected revision
 * matches its actual applied revision; otherwise it returns
 * {@link ApplyChainStatus#STALE_COMMAND}. A duplicate idempotency key returns
 * the previous result as {@link ApplyChainStatus#IDEMPOTENT_REPLAY}.
 *
 * <p>An unload is expressed as a command whose desired spec has
 * {@link ChainDesiredState#EMPTY} (and an empty rule list), <em>not</em> as a
 * coarse {@code RESET_ALL}.
 */
public final class ApplyChainRequest {

    private final String commandId;
    private final String idempotencyKey;
    private final RuleChainRevision expected;
    private final RuleChainSpec desired;
    private final List<MockRule> rules;
    private final EnhancementTarget target;
    private final long deadlineMillis;

    public ApplyChainRequest(String commandId, String idempotencyKey, RuleChainRevision expected,
                             RuleChainSpec desired, List<MockRule> rules,
                             EnhancementTarget target, long deadlineMillis) {
        this.commandId = requireText(commandId, "commandId");
        this.idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        this.expected = Objects.requireNonNull(expected, "expected");
        this.desired = Objects.requireNonNull(desired, "desired");
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.target = Objects.requireNonNullElse(target, desired.target());
        if (deadlineMillis < 0) {
            throw new IllegalArgumentException("deadlineMillis must be >= 0");
        }
        this.deadlineMillis = deadlineMillis;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public String commandId() {
        return commandId;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public RuleChainRevision expected() {
        return expected;
    }

    public RuleChainSpec desired() {
        return desired;
    }

    /** The full rule content (scripts + targets) to compile, in canonical order. */
    public List<MockRule> rules() {
        return rules;
    }

    public EnhancementTarget target() {
        return target;
    }

    public long deadlineMillis() {
        return deadlineMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String commandId;
        private String idempotencyKey;
        private RuleChainRevision expected;
        private RuleChainSpec desired;
        private List<MockRule> rules = List.of();
        private EnhancementTarget target;
        private long deadlineMillis;

        private Builder() {
        }

        public Builder commandId(String commandId) {
            this.commandId = commandId;
            return this;
        }

        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public Builder expected(RuleChainRevision expected) {
            this.expected = expected;
            return this;
        }

        public Builder desired(RuleChainSpec desired) {
            this.desired = desired;
            return this;
        }

        public Builder rules(List<MockRule> rules) {
            this.rules = rules;
            return this;
        }

        public Builder target(EnhancementTarget target) {
            this.target = target;
            return this;
        }

        public Builder deadlineMillis(long deadlineMillis) {
            this.deadlineMillis = deadlineMillis;
            return this;
        }

        public ApplyChainRequest build() {
            return new ApplyChainRequest(commandId, idempotencyKey, expected, desired, rules, target, deadlineMillis);
        }
    }
}
