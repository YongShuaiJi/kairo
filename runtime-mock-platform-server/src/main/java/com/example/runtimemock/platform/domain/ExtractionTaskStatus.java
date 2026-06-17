package com.example.runtimemock.platform.domain;

public enum ExtractionTaskStatus {
    DRAFT,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean canTransitionTo(ExtractionTaskStatus target) {
        return switch (this) {
            case DRAFT -> target == QUEUED || target == CANCELLED;
            case QUEUED -> target == RUNNING || target == CANCELLED;
            case RUNNING -> target == SUCCEEDED || target == FAILED || target == CANCELLED;
            case FAILED -> target == QUEUED || target == CANCELLED;
            case SUCCEEDED, CANCELLED -> false;
        };
    }
}
