package com.example.runtimemock.platform.domain;

public enum OperationPlanStatus {
    DRAFT,
    WAITING_APPROVAL,
    APPROVED,
    SCHEDULED,
    RUNNING,
    OBSERVING,
    SUCCEEDED,
    PARTIALLY_SUCCEEDED,
    FAILED,
    ROLLING_BACK,
    ROLLED_BACK,
    CANCELLED,
    EXPIRED;

    public boolean canTransitionTo(OperationPlanStatus target) {
        return switch (this) {
            case DRAFT -> target == WAITING_APPROVAL || target == CANCELLED;
            case WAITING_APPROVAL -> target == APPROVED || target == CANCELLED || target == EXPIRED;
            case APPROVED -> target == SCHEDULED || target == RUNNING || target == CANCELLED;
            case SCHEDULED -> target == RUNNING || target == CANCELLED || target == EXPIRED;
            case RUNNING -> target == OBSERVING
                    || target == SUCCEEDED
                    || target == PARTIALLY_SUCCEEDED
                    || target == FAILED
                    || target == ROLLING_BACK
                    || target == CANCELLED;
            case OBSERVING -> target == SUCCEEDED
                    || target == PARTIALLY_SUCCEEDED
                    || target == FAILED
                    || target == ROLLING_BACK;
            case PARTIALLY_SUCCEEDED, FAILED -> target == ROLLING_BACK || target == CANCELLED;
            case ROLLING_BACK -> target == ROLLED_BACK || target == FAILED;
            case SUCCEEDED -> target == ROLLING_BACK;
            case ROLLED_BACK, CANCELLED, EXPIRED -> false;
        };
    }
}
