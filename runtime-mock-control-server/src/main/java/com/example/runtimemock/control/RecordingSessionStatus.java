package com.example.runtimemock.control;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum RecordingSessionStatus {
    DRAFT,
    WAITING_APPROVAL,
    APPROVED,
    SCHEDULED,
    RECORDING,
    PAUSED,
    STOPPING,
    COMPLETED,
    FAILED,
    EXPIRED,
    CANCELLED;

    private static final Map<RecordingSessionStatus, Set<RecordingSessionStatus>> TRANSITIONS = Map.ofEntries(
            Map.entry(DRAFT, EnumSet.of(WAITING_APPROVAL, CANCELLED)),
            Map.entry(WAITING_APPROVAL, EnumSet.of(APPROVED, CANCELLED, EXPIRED)),
            Map.entry(APPROVED, EnumSet.of(SCHEDULED, RECORDING, CANCELLED, EXPIRED)),
            Map.entry(SCHEDULED, EnumSet.of(RECORDING, CANCELLED, EXPIRED)),
            Map.entry(RECORDING, EnumSet.of(PAUSED, STOPPING, COMPLETED, FAILED, EXPIRED)),
            Map.entry(PAUSED, EnumSet.of(RECORDING, STOPPING, COMPLETED, FAILED, EXPIRED, CANCELLED)),
            Map.entry(STOPPING, EnumSet.of(COMPLETED, FAILED)),
            Map.entry(COMPLETED, EnumSet.noneOf(RecordingSessionStatus.class)),
            Map.entry(FAILED, EnumSet.noneOf(RecordingSessionStatus.class)),
            Map.entry(EXPIRED, EnumSet.noneOf(RecordingSessionStatus.class)),
            Map.entry(CANCELLED, EnumSet.noneOf(RecordingSessionStatus.class))
    );

    boolean canTransitionTo(RecordingSessionStatus target) {
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }
}
