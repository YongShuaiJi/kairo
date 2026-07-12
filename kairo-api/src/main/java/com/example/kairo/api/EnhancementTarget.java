package com.example.kairo.api;

import java.util.Objects;

/**
 * Authoritative enhancement target: a method (or constructor) identity plus the
 * {@link EnhancementLocation} to attach to, and, for call-site locations, the
 * {@link CallSiteSelector} naming the exact invoke instruction.
 *
 * <p>V1.3 rules carry an {@code EnhancementTarget} as the source of truth. The
 * V1.2 {@link MethodSelector}/{@link InvokePhase} pair remains on {@link MockRule}
 * for backward compatibility and projects onto this model: a rule authored with
 * only a phase resolves to {@code EnhancementTarget(method, fromPhase(phase))}.
 *
 * <p>For constructor locations the {@code method} selector carries the
 * constructor identity &mdash; {@code methodName = "<init>"} and the constructor
 * descriptor &mdash; reusing {@link MethodSelector} rather than introducing a
 * synonymous constructor-identity type.
 */
public final class EnhancementTarget {

    private final MethodSelector method;
    private final EnhancementLocation location;
    private final CallSiteSelector callSiteSelector;

    private EnhancementTarget(MethodSelector method, EnhancementLocation location, CallSiteSelector callSiteSelector) {
        this.method = Objects.requireNonNull(method, "method");
        this.location = Objects.requireNonNull(location, "location");
        if (location.isCallSiteLocation() && callSiteSelector == null) {
            throw new IllegalArgumentException(
                    "callSiteSelector is required for call-site location " + location);
        }
        if (!location.isCallSiteLocation() && callSiteSelector != null) {
            throw new IllegalArgumentException(
                    "callSiteSelector must be null for non-call-site location " + location);
        }
        this.callSiteSelector = callSiteSelector;
    }

    public static EnhancementTarget of(MethodSelector method, EnhancementLocation location) {
        return new EnhancementTarget(method, location, null);
    }

    public static EnhancementTarget callSite(MethodSelector callerMethod, EnhancementLocation location,
                                             CallSiteSelector callSiteSelector) {
        if (!location.isCallSiteLocation()) {
            throw new IllegalArgumentException("callSite target requires a call-site location, got " + location);
        }
        return new EnhancementTarget(callerMethod, location, callSiteSelector);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Project a legacy V1.2 method selector + phase onto the authoritative model. */
    public static EnhancementTarget fromLegacy(MethodSelector method, InvokePhase phase) {
        return of(method, EnhancementLocation.fromPhase(phase));
    }

    public MethodSelector method() {
        return method;
    }

    public EnhancementLocation location() {
        return location;
    }

    public CallSiteSelector callSiteSelector() {
        return callSiteSelector;
    }

    public boolean isCallSite() {
        return location.isCallSiteLocation();
    }

    public boolean isConstructor() {
        return location.isConstructorLocation();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EnhancementTarget that)) {
            return false;
        }
        return method.equals(that.method)
                && location == that.location
                && Objects.equals(callSiteSelector, that.callSiteSelector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(method, location, callSiteSelector);
    }

    @Override
    public String toString() {
        return method + " @ " + location + (callSiteSelector == null ? "" : " call=" + callSiteSelector);
    }

    public static final class Builder {
        private MethodSelector method;
        private EnhancementLocation location;
        private CallSiteSelector callSiteSelector;

        private Builder() {
        }

        public Builder method(MethodSelector method) {
            this.method = method;
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

        public EnhancementTarget build() {
            return new EnhancementTarget(method, location, callSiteSelector);
        }
    }
}
