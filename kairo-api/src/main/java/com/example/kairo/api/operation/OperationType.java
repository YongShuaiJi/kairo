package com.example.kairo.api.operation;

/**
 * Category of a long-running {@link Operation} (V1.6 &sect;5.1). The unified
 * Operation service converges the previously scattered long tasks.
 */
public enum OperationType {
    AGENT_COMMAND,
    RULE_PUBLISH,
    RULE_ROLLBACK,
    RULE_UNLOAD,
    PREVIEW,
    SCRIPT_SESSION,
    RECONCILE,
    AUTOMATION_TRIAL,
    AUTOMATION_PROMOTE,
    AUTOMATION_REVERT
}
