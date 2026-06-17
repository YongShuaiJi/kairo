package com.example.runtimemock.control;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DatasetVersion(
        String datasetId,
        long version,
        String sourceSessionId,
        String schemaHash,
        String manifestHash,
        String maskingHash,
        String retentionPolicy,
        Instant createdAt,
        String createdBy,
        List<Map<String, Object>> objectReferences
) {
    String id() {
        return datasetId + ":" + version;
    }
}
