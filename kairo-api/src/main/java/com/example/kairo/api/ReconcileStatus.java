package com.example.kairo.api;

/**
 * Outcome of a reconciliation pass comparing the Platform's desired chain with
 * an Agent's actual chain.
 */
public enum ReconcileStatus {

    /** The Agent's actual revision and hash match the Platform's desired chain. */
    IN_SYNC,

    /** The Agent is behind the desired revision; an APPLY command is needed. */
    BEHIND,

    /** The Agent is ahead of or diverged from the desired revision; a rollback is needed. */
    AHEAD_OR_DIVERGED,

    /** The Agent holds no chain for the target (e.g. freshly restarted); a full apply is needed. */
    UNKNOWN
}
