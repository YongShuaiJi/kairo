package com.example.kairo.api;

import java.util.Objects;

/**
 * The Agent's response to an {@link ApplyChainRequest}.
 *
 * <p>Carries the {@link ApplyChainStatus}, the revision the Agent actually holds
 * after the command, the content hash it verified, and &mdash; on failure
 * &mdash; a message and the conflict report (when {@link ApplyChainStatus#REJECTED}).
 * The Platform converges its operation status from this result.
 */
public final class ApplyChainResult {

    private final ApplyChainStatus status;
    private final String commandId;
    private final RuleChainRevision applied;
    private final String actualHash;
    private final String message;
    private final ConflictReport conflictReport;

    public ApplyChainResult(ApplyChainStatus status, String commandId, RuleChainRevision applied,
                            String actualHash, String message, ConflictReport conflictReport) {
        this.status = Objects.requireNonNull(status, "status");
        this.commandId = Objects.requireNonNull(commandId, "commandId");
        this.applied = applied == null ? RuleChainRevision.initial() : applied;
        this.actualHash = actualHash == null ? "" : actualHash;
        this.message = message;
        this.conflictReport = conflictReport == null ? ConflictReport.empty() : conflictReport;
    }

    public static ApplyChainResult applied(String commandId, RuleChainRevision applied, String actualHash) {
        return new ApplyChainResult(ApplyChainStatus.APPLIED, commandId, applied, actualHash, null, null);
    }

    public static ApplyChainResult stale(String commandId, RuleChainRevision actual, String message) {
        return new ApplyChainResult(ApplyChainStatus.STALE_COMMAND, commandId, actual, actual.hash(), message, null);
    }

    public static ApplyChainResult replay(String commandId, RuleChainRevision actual, String actualHash) {
        return new ApplyChainResult(ApplyChainStatus.IDEMPOTENT_REPLAY, commandId, actual, actualHash,
                "duplicate command; previous result returned", null);
    }

    public static ApplyChainResult failed(String commandId, ApplyChainStatus status, RuleChainRevision actual,
                                          String message) {
        return new ApplyChainResult(status, commandId, actual, actual == null ? "" : actual.hash(), message, null);
    }

    public static ApplyChainResult rejected(String commandId, RuleChainRevision actual, ConflictReport report) {
        return new ApplyChainResult(ApplyChainStatus.REJECTED, commandId, actual,
                actual == null ? "" : actual.hash(), "conflict report blocked the chain", report);
    }

    public ApplyChainStatus status() {
        return status;
    }

    public String commandId() {
        return commandId;
    }

    public RuleChainRevision applied() {
        return applied;
    }

    public String actualHash() {
        return actualHash;
    }

    public String message() {
        return message;
    }

    public ConflictReport conflictReport() {
        return conflictReport;
    }

    public boolean succeeded() {
        return status == ApplyChainStatus.APPLIED
                || status == ApplyChainStatus.NO_OP
                || status == ApplyChainStatus.DEGRADED;
    }
}
