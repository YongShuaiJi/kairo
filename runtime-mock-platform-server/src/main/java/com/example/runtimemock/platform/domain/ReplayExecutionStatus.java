package com.example.runtimemock.platform.domain;

public enum ReplayExecutionStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(ReplayExecutionStatus target) {
        return switch (this) {
            case QUEUED -> target == RUNNING || target == CANCELLED;
            case RUNNING -> target == PAUSED || target == SUCCEEDED || target == FAILED || target == CANCELLED;
            case PAUSED -> target == RUNNING || target == CANCELLED;
            case FAILED -> target == QUEUED || target == CANCELLED;
            case SUCCEEDED, CANCELLED -> false;
        };
    }
}
