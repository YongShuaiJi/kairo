package com.example.kairo.agent.core.script;

import java.util.Objects;

/**
 * Safety bounds the agent enforces on every temporary {@code ScriptSession}. A trial session is
 * deliberately constrained: by default a single active session per target method, a low hit cap
 * and a short time-to-live. The Platform may relax these upper bounds for a deployment, but the
 * agent never accepts a session that exceeds them, so a misbehaving or compromised control plane
 * cannot open an unbounded trial.
 *
 * <p>These are upper bounds, not defaults applied silently: a {@link com.example.kairo.api.ScriptSessionSpec}
 * that asks for a larger TTL or hit cap than configured is rejected at creation rather than
 * silently clamped, so the caller learns the limit instead of running with reduced scope.
 */
public final class ScriptSessionLimits {

    /** Default upper bound on a session's TTL: five minutes. */
    public static final long DEFAULT_MAX_TTL_MILLIS = 5L * 60L * 1000L;

    /** Default upper bound on a session's hit cap: one hundred matched invocations. */
    public static final long DEFAULT_MAX_HITS_CAP = 100L;

    /** Default cap on concurrent non-terminal sessions for the same target method: one. */
    public static final int DEFAULT_MAX_CONCURRENT_PER_TARGET = 1;

    /** Default cap on the total number of non-terminal sessions the agent will hold: sixteen. */
    public static final int DEFAULT_MAX_TOTAL_SESSIONS = 16;

    private final long maxTtlMillis;
    private final long maxHitsCap;
    private final int maxConcurrentPerTarget;
    private final int maxTotalSessions;

    private ScriptSessionLimits(Builder builder) {
        this.maxTtlMillis = requirePositive(builder.maxTtlMillis, "maxTtlMillis");
        this.maxHitsCap = requirePositive(builder.maxHitsCap, "maxHitsCap");
        this.maxConcurrentPerTarget = requirePositive(builder.maxConcurrentPerTarget, "maxConcurrentPerTarget");
        this.maxTotalSessions = requirePositive(builder.maxTotalSessions, "maxTotalSessions");
    }

    public static ScriptSessionLimits defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Maximum TTL a session may request. */
    public long maxTtlMillis() {
        return maxTtlMillis;
    }

    /** Maximum hit cap a session may request. */
    public long maxHitsCap() {
        return maxHitsCap;
    }

    /** Maximum concurrent non-terminal sessions allowed for one target method. */
    public int maxConcurrentPerTarget() {
        return maxConcurrentPerTarget;
    }

    /** Maximum concurrent non-terminal sessions the agent will hold in total. */
    public int maxTotalSessions() {
        return maxTotalSessions;
    }

    private static long requirePositive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return value;
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return value;
    }

    public static final class Builder {
        private long maxTtlMillis = DEFAULT_MAX_TTL_MILLIS;
        private long maxHitsCap = DEFAULT_MAX_HITS_CAP;
        private int maxConcurrentPerTarget = DEFAULT_MAX_CONCURRENT_PER_TARGET;
        private int maxTotalSessions = DEFAULT_MAX_TOTAL_SESSIONS;

        private Builder() {
        }

        public Builder maxTtlMillis(long maxTtlMillis) {
            this.maxTtlMillis = maxTtlMillis;
            return this;
        }

        public Builder maxHitsCap(long maxHitsCap) {
            this.maxHitsCap = maxHitsCap;
            return this;
        }

        public Builder maxConcurrentPerTarget(int maxConcurrentPerTarget) {
            this.maxConcurrentPerTarget = maxConcurrentPerTarget;
            return this;
        }

        public Builder maxTotalSessions(int maxTotalSessions) {
            this.maxTotalSessions = maxTotalSessions;
            return this;
        }

        public ScriptSessionLimits build() {
            return new ScriptSessionLimits(this);
        }
    }

    @Override
    public String toString() {
        return "ScriptSessionLimits{maxTtlMillis=" + maxTtlMillis
                + ", maxHitsCap=" + maxHitsCap
                + ", maxConcurrentPerTarget=" + maxConcurrentPerTarget
                + ", maxTotalSessions=" + maxTotalSessions + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ScriptSessionLimits that)) {
            return false;
        }
        return maxTtlMillis == that.maxTtlMillis
                && maxHitsCap == that.maxHitsCap
                && maxConcurrentPerTarget == that.maxConcurrentPerTarget
                && maxTotalSessions == that.maxTotalSessions;
    }

    @Override
    public int hashCode() {
        return Objects.hash(maxTtlMillis, maxHitsCap, maxConcurrentPerTarget, maxTotalSessions);
    }
}
