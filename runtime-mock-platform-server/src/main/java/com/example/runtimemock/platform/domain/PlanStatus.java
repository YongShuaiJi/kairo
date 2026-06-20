package com.example.runtimemock.platform.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PlanStatus {
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

    private static final Map<PlanStatus, Set<PlanStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(DRAFT, EnumSet.of(WAITING_APPROVAL, CANCELLED)),
            Map.entry(WAITING_APPROVAL, EnumSet.of(APPROVED, CANCELLED, EXPIRED)),
            Map.entry(APPROVED, EnumSet.of(SCHEDULED, RUNNING, CANCELLED, EXPIRED)),
            Map.entry(SCHEDULED, EnumSet.of(RUNNING, CANCELLED, EXPIRED)),
            Map.entry(RUNNING, EnumSet.of(OBSERVING, SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, ROLLING_BACK)),
            Map.entry(OBSERVING, EnumSet.of(SUCCEEDED, PARTIALLY_SUCCEEDED, FAILED, ROLLING_BACK)),
            Map.entry(PARTIALLY_SUCCEEDED, EnumSet.of(ROLLING_BACK)),
            Map.entry(FAILED, EnumSet.of(ROLLING_BACK)),
            Map.entry(ROLLING_BACK, EnumSet.of(ROLLED_BACK, FAILED)),
            Map.entry(SUCCEEDED, EnumSet.of(ROLLING_BACK)),
            Map.entry(ROLLED_BACK, EnumSet.noneOf(PlanStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(PlanStatus.class)),
            Map.entry(EXPIRED, EnumSet.noneOf(PlanStatus.class))
    );

    public boolean canTransitionTo(PlanStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
