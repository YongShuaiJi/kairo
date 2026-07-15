package com.example.kairo.api.automation;

/**
 * Lifecycle status of an {@link AutomationSession} (V1.6 &sect;4.1).
 */
public enum AutomationSessionStatus {
    /** Created, not yet used for any task. */
    CREATED,
    /** At least one task has been initiated within the session. */
    ACTIVE,
    /** All tasks completed and the session cleaned up normally. */
    COMPLETED,
    /** TTL/deadline expired; platform-initiated cleanup ran. */
    EXPIRED,
    /** One-click revert completed; created resources were rolled back. */
    REVERTED,
    /** A task failed terminally and the session is no longer usable. */
    FAILED
}
