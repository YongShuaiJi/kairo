package com.example.kairo.platform.domain;

public enum OperationPlanStatus {
    DRAFT,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNLOADING,
    UNLOADED,
    ABANDONED;

    public boolean canTransitionTo(OperationPlanStatus target) {
        return switch (this) {
            case DRAFT -> target == RUNNING;
            case RUNNING, SUCCEEDED, FAILED, UNLOADING, UNLOADED, ABANDONED -> false;
        };
    }
}
