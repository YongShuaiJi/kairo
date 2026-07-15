package com.example.kairo.platform.api.dto;

import java.util.Map;

/**
 * Strongly-typed request for {@code POST /api/v1/instances} (V1.6 &sect;2.2 / &sect;5.1:
 * replace {@code Map<String,Object>} on core write paths with explicit DTOs). The
 * controller binds this record directly from the JSON body, so the OpenAPI contract
 * publishes a concrete schema instead of a free-form object.
 *
 * @param applicationId  owning application (required)
 * @param environmentId  assigned environment (optional)
 * @param id             caller-supplied id (optional; generated when absent)
 * @param nickname       display nickname (optional; derived when absent)
 * @param hostname       host name (required)
 * @param processId      process id (optional)
 * @param runtime        runtime label, e.g. {@code java} (optional)
 * @param status         initial status (optional; defaults to ACTIVE)
 * @param labels         labels map (optional)
 * @param reason         audit reason (optional)
 */
public record CreateInstanceRequest(
        String applicationId,
        String environmentId,
        String id,
        String nickname,
        String hostname,
        String processId,
        String runtime,
        String status,
        Map<String, Object> labels,
        String reason
) {
}
