package com.example.kairo.platform.api.dto;

/** Strongly-typed request for {@code POST /api/v1/fencing-tokens} (V1.6 §2.2). */
public record IssueFencingTokenRequest(
        String resourceType,
        String resourceId,
        String purpose,
        Long ttlSeconds,
        String reason
) {
}
