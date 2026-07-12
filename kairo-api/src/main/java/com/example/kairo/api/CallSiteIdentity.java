package com.example.kairo.api;

import java.util.Objects;

/**
 * Resolved, stable identity of a call site: the caller method identity plus the
 * {@link CallSiteSelector}. This is what the agent produces when scanning a
 * caller method and what is compared at publish / re-resolve time to detect
 * drift.
 *
 * <p>Two identities are equal when their caller and selector <em>core</em>
 * (owner + name + descriptor + opcode + occurrenceIndex) match. The
 * {@code fingerprint} is compared separately via {@link #fingerprintMatches} so
 * a recompiled caller that still contains the call site at the same occurrence
 * &mdash; but with a different surrounding instruction sequence &mdash; is
 * reported as drifted rather than as a different site.
 */
public final class CallSiteIdentity {

    private final MethodSelector caller;
    private final CallSiteSelector selector;

    public CallSiteIdentity(MethodSelector caller, CallSiteSelector selector) {
        this.caller = Objects.requireNonNull(caller, "caller");
        this.selector = Objects.requireNonNull(selector, "selector");
    }

    public MethodSelector caller() {
        return caller;
    }

    public CallSiteSelector selector() {
        return selector;
    }

    /**
     * Whether the recorded fingerprint is consistent with the freshly resolved
     * one. A {@code null} on either side means no fingerprint was captured, so
     * the check is skipped (treated as matching) &mdash; drift can only be
     * detected when both sides carry a fingerprint.
     */
    public boolean fingerprintMatches(CallSiteIdentity resolved) {
        if (resolved == null) {
            return false;
        }
        String recorded = selector.fingerprint();
        String fresh = resolved.selector().fingerprint();
        if (recorded == null || fresh == null) {
            return true;
        }
        return recorded.equals(fresh);
    }

    /** Core equality: caller + selector core, ignoring the fingerprint. */
    public boolean coreEquals(CallSiteIdentity other) {
        if (other == null) {
            return false;
        }
        return caller.equals(other.caller) && selector.coreEquals(other.selector);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CallSiteIdentity that)) {
            return false;
        }
        return caller.equals(that.caller) && selector.equals(that.selector);
    }

    @Override
    public int hashCode() {
        return Objects.hash(caller, selector);
    }

    @Override
    public String toString() {
        return caller + " -> " + selector;
    }
}
