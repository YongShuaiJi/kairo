package com.example.kairo.api;

import java.util.Objects;

/**
 * Outcome of resolving an {@link EnhancementTarget} against live bytecode.
 *
 * <p>The agent produces this when scanning a class for a target so the platform
 * can refuse to save a rule whose target has drifted, points at an unenhanceable
 * member, or no longer exists &mdash; instead of silently weaving the wrong
 * location.
 */
public final class TargetMatchResult {

    public enum Status {
        /** The target was found and is enhanceable; a call-site target also resolved to a stable identity. */
        MATCHED,
        /** The class or method/constructor/call site was not found in the live bytecode. */
        NOT_FOUND,
        /** The call site core still matches but the surrounding-instruction fingerprint changed. */
        DRIFTED,
        /** The target exists but cannot be enhanced (native, abstract, unmodifiable class, unsupported opcode). */
        REJECTED
    }

    private final Status status;
    private final int matchedCount;
    private final String reason;
    private final CallSiteIdentity resolvedIdentity;

    private TargetMatchResult(Status status, int matchedCount, String reason, CallSiteIdentity resolvedIdentity) {
        this.status = Objects.requireNonNull(status, "status");
        this.matchedCount = matchedCount;
        this.reason = reason;
        this.resolvedIdentity = resolvedIdentity;
    }

    public static TargetMatchResult matched(int matchedCount) {
        return new TargetMatchResult(Status.MATCHED, matchedCount, null, null);
    }

    public static TargetMatchResult matchedCallSite(CallSiteIdentity resolvedIdentity) {
        return new TargetMatchResult(Status.MATCHED, 1, null, resolvedIdentity);
    }

    public static TargetMatchResult notFound(String reason) {
        return new TargetMatchResult(Status.NOT_FOUND, 0, reason, null);
    }

    public static TargetMatchResult drifted(String reason, CallSiteIdentity resolvedIdentity) {
        return new TargetMatchResult(Status.DRIFTED, 1, reason, resolvedIdentity);
    }

    public static TargetMatchResult rejected(String reason) {
        return new TargetMatchResult(Status.REJECTED, 0, reason, null);
    }

    public Status status() {
        return status;
    }

    public int matchedCount() {
        return matchedCount;
    }

    public String reason() {
        return reason;
    }

    public CallSiteIdentity resolvedIdentity() {
        return resolvedIdentity;
    }

    public boolean isMatched() {
        return status == Status.MATCHED;
    }

    @Override
    public String toString() {
        return status + (reason == null ? "" : ": " + reason);
    }
}
