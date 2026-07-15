package com.example.kairo.platform.api.dto;

import java.util.List;

/** Strongly-typed request for {@code POST /api/v1/sidecars} (V1.6 §2.2). */
public record CreateSidecarRequest(
        String id,
        String instanceId,
        String status,
        String sidecarVersion,
        String endpoint,
        List<String> capabilities,
        String reason
) {
}
