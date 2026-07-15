package com.example.kairo.platform.api.dto;

import java.util.List;

/** Strongly-typed request for {@code POST /api/v1/agents} (V1.6 §2.2). */
public record CreateAgentRequest(
        String id,
        String instanceId,
        String sidecarId,
        String status,
        String agentVersion,
        String bootstrapVersion,
        String listenHost,
        Integer listenPort,
        String tokenHash,
        List<String> capabilities,
        String reason
) {
}
