package com.example.runtimemock.api;

import java.util.Objects;

public final class MockRule {

    private final String id;
    private final long version;
    private final String name;
    private final String description;
    private final MethodSelector target;
    private final InvokePhase phase;
    private final String script;
    private final String scriptHash;
    private final int priority;
    private final int percentage;
    private final long maxHits;
    private final long expireAt;
    private final boolean failOpen;
    private final boolean enabled;

    private MockRule(Builder builder) {
        this.id = requireText(builder.id, "id");
        this.version = builder.version;
        this.name = builder.name == null ? id : builder.name;
        this.description = builder.description;
        this.target = Objects.requireNonNull(builder.target, "target");
        this.phase = Objects.requireNonNull(builder.phase, "phase");
        this.script = requireText(builder.script, "script");
        this.scriptHash = builder.scriptHash;
        this.priority = builder.priority;
        this.percentage = validatePercentage(builder.percentage);
        this.maxHits = builder.maxHits;
        this.expireAt = builder.expireAt;
        this.failOpen = builder.failOpen;
        this.enabled = builder.enabled;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static int validatePercentage(int percentage) {
        if (percentage < 0 || percentage > 100) {
            throw new IllegalArgumentException("percentage must be between 0 and 100");
        }
        return percentage;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return builder()
                .id(id)
                .version(version)
                .name(name)
                .description(description)
                .target(target)
                .phase(phase)
                .script(script)
                .scriptHash(scriptHash)
                .priority(priority)
                .percentage(percentage)
                .maxHits(maxHits)
                .expireAt(expireAt)
                .failOpen(failOpen)
                .enabled(enabled);
    }

    public String id() {
        return id;
    }

    public long version() {
        return version;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public MethodSelector target() {
        return target;
    }

    public InvokePhase phase() {
        return phase;
    }

    public String script() {
        return script;
    }

    public String scriptHash() {
        return scriptHash;
    }

    public int priority() {
        return priority;
    }

    public int percentage() {
        return percentage;
    }

    public long maxHits() {
        return maxHits;
    }

    public long expireAt() {
        return expireAt;
    }

    public boolean failOpen() {
        return failOpen;
    }

    public boolean enabled() {
        return enabled;
    }

    public static final class Builder {
        private String id;
        private long version = 1L;
        private String name;
        private String description;
        private MethodSelector target;
        private InvokePhase phase;
        private String script;
        private String scriptHash;
        private int priority;
        private int percentage = 100;
        private long maxHits;
        private long expireAt;
        private boolean failOpen = true;
        private boolean enabled = true;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder version(long version) {
            this.version = version;
            return this;
        }

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder target(MethodSelector target) {
            this.target = target;
            return this;
        }

        public Builder phase(InvokePhase phase) {
            this.phase = phase;
            return this;
        }

        public Builder script(String script) {
            this.script = script;
            return this;
        }

        public Builder scriptHash(String scriptHash) {
            this.scriptHash = scriptHash;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder percentage(int percentage) {
            this.percentage = percentage;
            return this;
        }

        public Builder maxHits(long maxHits) {
            this.maxHits = maxHits;
            return this;
        }

        public Builder expireAt(long expireAt) {
            this.expireAt = expireAt;
            return this;
        }

        public Builder failOpen(boolean failOpen) {
            this.failOpen = failOpen;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public MockRule build() {
            return new MockRule(this);
        }
    }
}
