package com.example.kairo.api;

import java.util.List;
import java.util.Objects;

/**
 * Result of reconciling an Agent's actual chain against the Platform's desired
 * chain. Carries the status, the actual and desired revision tokens, and a list
 * of recommended actions (e.g. {@code APPLY}, {@code ROLLBACK}) the Platform
 * should issue to converge.
 */
public final class ReconcileResult {

    private final ReconcileStatus status;
    private final RuleChainRevision actual;
    private final RuleChainRevision desired;
    private final List<String> actions;

    public ReconcileResult(ReconcileStatus status, RuleChainRevision actual, RuleChainRevision desired,
                           List<String> actions) {
        this.status = Objects.requireNonNull(status, "status");
        this.actual = actual == null ? RuleChainRevision.initial() : actual;
        this.desired = desired == null ? RuleChainRevision.initial() : desired;
        this.actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public static ReconcileResult inSync(RuleChainRevision actual, RuleChainRevision desired) {
        return new ReconcileResult(ReconcileStatus.IN_SYNC, actual, desired, List.of());
    }

    public static ReconcileResult behind(RuleChainRevision actual, RuleChainRevision desired) {
        return new ReconcileResult(ReconcileStatus.BEHIND, actual, desired, List.of("APPLY"));
    }

    public static ReconcileResult aheadOrDiverged(RuleChainRevision actual, RuleChainRevision desired) {
        return new ReconcileResult(ReconcileStatus.AHEAD_OR_DIVERGED, actual, desired, List.of("ROLLBACK"));
    }

    public static ReconcileResult unknown(RuleChainRevision desired) {
        return new ReconcileResult(ReconcileStatus.UNKNOWN, RuleChainRevision.initial(), desired, List.of("APPLY"));
    }

    public ReconcileStatus status() {
        return status;
    }

    public RuleChainRevision actual() {
        return actual;
    }

    public RuleChainRevision desired() {
        return desired;
    }

    public List<String> actions() {
        return actions;
    }
}
