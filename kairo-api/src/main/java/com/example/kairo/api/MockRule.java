package com.example.kairo.api;

import java.util.Objects;

public final class MockRule {

    private final String id;
    private final long version;
    private final String name;
    private final String description;
    private final MethodSelector target;
    private final InvokePhase phase;
    private final EnhancementLocation location;
    private final CallSiteSelector callSiteSelector;
    private final String script;
    private final String scriptHash;
    private final int priority;
    private final int percentage;
    private final long maxHits;
    private final long expireAt;
    private final boolean failOpen;
    private final boolean enabled;
    private final CapabilityProfile capabilityProfile;
    private final ScriptPolicyRevision policyRevision;
    private final int consecutiveFailureThreshold;
    private final String scriptSessionSource;

    private MockRule(Builder builder) {
        this.id = requireText(builder.id, "id");
        this.version = builder.version;
        this.name = builder.name == null ? id : builder.name;
        this.description = builder.description;
        this.target = Objects.requireNonNull(builder.target, "target");
        this.location = builder.location;
        this.callSiteSelector = builder.callSiteSelector;
        InvokePhase resolvedPhase = builder.phase;
        if (resolvedPhase == null) {
            if (this.location == null) {
                throw new IllegalArgumentException("phase or location must be set");
            }
            resolvedPhase = this.location.toLegacyPhase();
        }
        this.phase = resolvedPhase;
        if (this.location != null && this.callSiteSelector == null && this.location.isCallSiteLocation()) {
            throw new IllegalArgumentException("callSiteSelector is required for call-site location " + this.location);
        }
        if (this.callSiteSelector != null && (this.location == null || !this.location.isCallSiteLocation())) {
            throw new IllegalArgumentException("callSiteSelector requires a call-site location");
        }
        this.script = requireText(builder.script, "script");
        this.scriptHash = builder.scriptHash;
        this.priority = builder.priority;
        this.percentage = validatePercentage(builder.percentage);
        this.maxHits = builder.maxHits;
        this.expireAt = builder.expireAt;
        this.failOpen = builder.failOpen;
        this.enabled = builder.enabled;
        this.capabilityProfile = Objects.requireNonNull(builder.capabilityProfile, "capabilityProfile");
        this.policyRevision = builder.policyRevision;
        if (builder.consecutiveFailureThreshold <= 0) {
            throw new IllegalArgumentException("consecutiveFailureThreshold must be > 0");
        }
        this.consecutiveFailureThreshold = builder.consecutiveFailureThreshold;
        if (builder.scriptSessionSource != null && builder.scriptSessionSource.isBlank()) {
            throw new IllegalArgumentException("scriptSessionSource must be null or non-blank");
        }
        this.scriptSessionSource = builder.scriptSessionSource;
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
                .location(location)
                .callSiteSelector(callSiteSelector)
                .script(script)
                .scriptHash(scriptHash)
                .priority(priority)
                .percentage(percentage)
                .maxHits(maxHits)
                .expireAt(expireAt)
                .failOpen(failOpen)
                .enabled(enabled)
                .capabilityProfile(capabilityProfile)
                .policyRevision(policyRevision)
                .consecutiveFailureThreshold(consecutiveFailureThreshold)
                .scriptSessionSource(scriptSessionSource);
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

    /**
     * Explicit V1.3 enhancement location, or {@code null} when the rule was
     * authored against the legacy {@link #phase()} only. Use
     * {@link #effectiveLocation()} for the resolved authoritative location.
     */
    public EnhancementLocation location() {
        return location;
    }

    public CallSiteSelector callSiteSelector() {
        return callSiteSelector;
    }

    /**
     * The authoritative enhancement location: the explicit {@link #location()}
     * when set, otherwise the location projected from the legacy {@link #phase()}.
     * V1.0/V1.2 rules without a location resolve to METHOD_ENTER / METHOD_RETURN /
     * METHOD_THROW and behave exactly as before.
     */
    public EnhancementLocation effectiveLocation() {
        return location != null ? location : EnhancementLocation.fromPhase(phase);
    }

    /**
     * The authoritative V1.3 enhancement target, built from the rule's method
     * selector, effective location and (for call-site rules) call-site selector.
     */
    public EnhancementTarget enhancementTarget() {
        if (callSiteSelector != null) {
            return EnhancementTarget.callSite(target, effectiveLocation(), callSiteSelector);
        }
        return EnhancementTarget.of(target, effectiveLocation());
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

    public CapabilityProfile capabilityProfile() {
        return capabilityProfile;
    }

    public ScriptPolicyRevision policyRevision() {
        return policyRevision;
    }

    public int consecutiveFailureThreshold() {
        return consecutiveFailureThreshold;
    }

    public String scriptSessionSource() {
        return scriptSessionSource;
    }

    public static final class Builder {
        private String id;
        private long version = 1L;
        private String name;
        private String description;
        private MethodSelector target;
        private InvokePhase phase;
        private EnhancementLocation location;
        private CallSiteSelector callSiteSelector;
        private String script;
        private String scriptHash;
        private int priority;
        private int percentage = 100;
        private long maxHits;
        private long expireAt;
        private boolean failOpen = true;
        private boolean enabled = true;
        private CapabilityProfile capabilityProfile = CapabilityProfile.SAFE;
        private ScriptPolicyRevision policyRevision;
        private int consecutiveFailureThreshold = 3;
        private String scriptSessionSource;

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

        public Builder location(EnhancementLocation location) {
            this.location = location;
            return this;
        }

        public Builder callSiteSelector(CallSiteSelector callSiteSelector) {
            this.callSiteSelector = callSiteSelector;
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

        public Builder capabilityProfile(CapabilityProfile capabilityProfile) {
            this.capabilityProfile = capabilityProfile;
            return this;
        }

        public Builder policyRevision(ScriptPolicyRevision policyRevision) {
            this.policyRevision = policyRevision;
            return this;
        }

        public Builder consecutiveFailureThreshold(int consecutiveFailureThreshold) {
            this.consecutiveFailureThreshold = consecutiveFailureThreshold;
            return this;
        }

        public Builder scriptSessionSource(String scriptSessionSource) {
            this.scriptSessionSource = scriptSessionSource;
            return this;
        }

        public MockRule build() {
            return new MockRule(this);
        }
    }
}
