package com.example.kairo.platform.api.dto;

import java.util.Map;

/** Strongly-typed request for {@code POST /api/v1/operation-plans} (V1.6 §2.2). */
public record CreateOperationPlanRequest(
        String id,
        String applicationId,
        String environmentId,
        String resourceType,
        String resourceId,
        Long resourceVersion,
        String planType,
        Map<String, Object> strategy,
        String reason
) {
}
