package com.example.runtimemock.control;

import java.time.Instant;
import java.util.Map;

public record AuditRecord(
        String id,
        Instant occurredAt,
        String actor,
        String action,
        String resourceType,
        String resourceId,
        long resourceVersion,
        String previousRecordHash,
        String recordHash,
        String correlationId,
        String result,
        String reason,
        Map<String, Object> details
) {
}
