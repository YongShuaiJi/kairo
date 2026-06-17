package com.example.runtimemock.control;

import java.time.Instant;
import java.util.Map;

public record ReplayPlan(
        String id,
        long version,
        String datasetId,
        long datasetVersion,
        String targetEnvironment,
        String targetApplication,
        PlanStatus status,
        String sideEffectPolicyHash,
        String comparisonPolicyHash,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        Map<String, Object> executionPolicy
) {
    ReplayPlan transitionTo(PlanStatus targetStatus, Instant now) {
        return new ReplayPlan(
                id,
                version + 1,
                datasetId,
                datasetVersion,
                targetEnvironment,
                targetApplication,
                targetStatus,
                sideEffectPolicyHash,
                comparisonPolicyHash,
                createdAt,
                now,
                createdBy,
                executionPolicy
        );
    }
}
