package com.example.kairo.api.operation;

/**
 * Lifecycle status of a unified {@link Operation} (V1.6 &sect;2.2 / &sect;5.1).
 */
public enum OperationStatus {
    /** Created, not yet dispatched. */
    PENDING,
    /** Dispatched to an agent or scheduler and in progress. */
    RUNNING,
    /** Completed successfully. */
    SUCCEEDED,
    /** Completed with failure; see {@code error}. */
    FAILED,
    /** Cancelled by the caller before completion. */
    CANCELLED,
    /** A previously succeeded operation was reverted. */
    REVERTED,
    /** Did not complete within the deadline. */
    TIMEOUT
}
