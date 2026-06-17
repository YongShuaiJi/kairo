package com.example.runtimemock.control;

import java.time.Instant;
import java.util.Map;

public record RecordingSession(
        String id,
        String application,
        String environment,
        RecordingSessionStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        long maxEvents,
        long ttlSeconds,
        Map<String, Object> target,
        Map<String, Object> quota
) {
    RecordingSession transitionTo(RecordingSessionStatus targetStatus, Instant now) {
        return new RecordingSession(
                id,
                application,
                environment,
                targetStatus,
                version + 1,
                createdAt,
                now,
                createdBy,
                maxEvents,
                ttlSeconds,
                target,
                quota
        );
    }
}
