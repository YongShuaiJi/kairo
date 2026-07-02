package com.example.runtimemock.platform.domain;

public enum OperationPlanStatus {
    DRAFT,
    RUNNING,
    SUCCEEDED,
    FAILED,
    UNLOADING,
    UNLOADED;

    public boolean canTransitionTo(OperationPlanStatus target) {
        return switch (this) {
            case DRAFT -> target == RUNNING;
            case RUNNING, SUCCEEDED, FAILED, UNLOADING, UNLOADED -> false;
        };
    }
}
