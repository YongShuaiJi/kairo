package com.example.kairo.core;

import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Configuration for {@link RuleDispatcher}'s bounded script executor and timeout policy.
 *
 * <p>Replaces the hardcoded constants that previously lived on {@code RuleDispatcher}. Every
 * knob that affects fail-open behaviour &mdash; the per-invocation timeout, the longer
 * first-run timeout, the executor pool shape and the rejection semantics &mdash; is now
 * explicit so it can be tuned per deployment and exercised by tests without depending on
 * wall-clock defaults.
 *
 * <p>The defaults reproduce the previous hardcoded behaviour exactly: a 100&nbsp;ms steady-state
 * timeout, a 1&nbsp;s first-run timeout, and a cached pool with a {@link java.util.concurrent.SynchronousQueue}
 * that hands off to daemon threads up to {@code max(4, availableProcessors)} and rejects once
 * saturated (the prior {@link ThreadPoolExecutor.AbortPolicy}).
 */
public final class RuleDispatcherConfig {

    private final long scriptTimeoutMillis;
    private final long firstScriptTimeoutMillis;
    private final int executorCorePoolSize;
    private final int executorMaxPoolSize;
    private final long executorKeepAliveSeconds;
    private final int executorQueueCapacity;
    private final String threadNamePrefix;

    private RuleDispatcherConfig(Builder builder) {
        this.scriptTimeoutMillis = requirePositive(builder.scriptTimeoutMillis, "scriptTimeoutMillis");
        this.firstScriptTimeoutMillis = requirePositive(builder.firstScriptTimeoutMillis, "firstScriptTimeoutMillis");
        this.executorCorePoolSize = requireNonNegative(builder.executorCorePoolSize, "executorCorePoolSize");
        this.executorMaxPoolSize = requirePositive(builder.executorMaxPoolSize, "executorMaxPoolSize");
        if (builder.executorMaxPoolSize < builder.executorCorePoolSize) {
            throw new IllegalArgumentException(
                    "executorMaxPoolSize must be >= executorCorePoolSize");
        }
        this.executorKeepAliveSeconds = requirePositive(builder.executorKeepAliveSeconds, "executorKeepAliveSeconds");
        this.executorQueueCapacity = requireNonNegative(builder.executorQueueCapacity, "executorQueueCapacity");
        this.threadNamePrefix = Objects.requireNonNullElse(builder.threadNamePrefix, "kairo-script-execution");
    }

    /**
     * Defaults that reproduce the previous hardcoded {@code RuleDispatcher} behaviour: 100&nbsp;ms
     * steady-state timeout, 1&nbsp;s first-run timeout, core 0, max {@code max(4, cores)}, 30&nbsp;s
     * keep-alive, a zero-capacity (synchronous) queue and the {@code kairo-script-execution}
     * thread name prefix.
     */
    public static RuleDispatcherConfig defaults() {
        return builder()
                .scriptTimeoutMillis(100L)
                .firstScriptTimeoutMillis(1_000L)
                .executorCorePoolSize(0)
                .executorMaxPoolSize(Math.max(4, Runtime.getRuntime().availableProcessors()))
                .executorKeepAliveSeconds(30L)
                .executorQueueCapacity(0)
                .threadNamePrefix("kairo-script-execution")
                .build();
    }

    public long scriptTimeoutMillis() {
        return scriptTimeoutMillis;
    }

    public long firstScriptTimeoutMillis() {
        return firstScriptTimeoutMillis;
    }

    public int executorCorePoolSize() {
        return executorCorePoolSize;
    }

    public int executorMaxPoolSize() {
        return executorMaxPoolSize;
    }

    public long executorKeepAliveSeconds() {
        return executorKeepAliveSeconds;
    }

    /**
     * Bounded queue capacity. {@code 0} selects a {@link java.util.concurrent.SynchronousQueue}
     * (direct handoff, reject when no idle thread); a positive value selects a bounded
     * {@link java.util.concurrent.LinkedBlockingQueue} that buffers up to that many tasks.
     */
    public int executorQueueCapacity() {
        return executorQueueCapacity;
    }

    public String threadNamePrefix() {
        return threadNamePrefix;
    }

    public static Builder builder() {
        return new Builder();
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

    private static int requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be >= 0");
        }
        return value;
    }

    public static final class Builder {
        private long scriptTimeoutMillis = 100L;
        private long firstScriptTimeoutMillis = 1_000L;
        private int executorCorePoolSize = 0;
        private int executorMaxPoolSize = Math.max(4, Runtime.getRuntime().availableProcessors());
        private long executorKeepAliveSeconds = 30L;
        private int executorQueueCapacity = 0;
        private String threadNamePrefix = "kairo-script-execution";

        private Builder() {
        }

        public Builder scriptTimeoutMillis(long scriptTimeoutMillis) {
            this.scriptTimeoutMillis = scriptTimeoutMillis;
            return this;
        }

        public Builder firstScriptTimeoutMillis(long firstScriptTimeoutMillis) {
            this.firstScriptTimeoutMillis = firstScriptTimeoutMillis;
            return this;
        }

        public Builder executorCorePoolSize(int executorCorePoolSize) {
            this.executorCorePoolSize = executorCorePoolSize;
            return this;
        }

        public Builder executorMaxPoolSize(int executorMaxPoolSize) {
            this.executorMaxPoolSize = executorMaxPoolSize;
            return this;
        }

        public Builder executorKeepAliveSeconds(long executorKeepAliveSeconds) {
            this.executorKeepAliveSeconds = executorKeepAliveSeconds;
            return this;
        }

        public Builder executorQueueCapacity(int executorQueueCapacity) {
            this.executorQueueCapacity = executorQueueCapacity;
            return this;
        }

        public Builder threadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
            return this;
        }

        public RuleDispatcherConfig build() {
            return new RuleDispatcherConfig(this);
        }
    }
}
