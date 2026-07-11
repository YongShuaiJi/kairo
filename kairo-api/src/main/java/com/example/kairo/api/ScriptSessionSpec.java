package com.example.kairo.api;

import java.util.Objects;

/** Requested scope and safety limits for a temporary script session. */
public record ScriptSessionSpec(
        String sessionId,
        String agentId,
        MethodSelector target,
        String script,
        CapabilityProfile capabilityProfile,
        ScriptPolicyRevision policyRevision,
        long ttlMillis,
        long maxHits,
        String requestedBy
) {
    public ScriptSessionSpec {
        sessionId = requireText(sessionId, "sessionId");
        agentId = requireText(agentId, "agentId");
        Objects.requireNonNull(target, "target");
        script = requireText(script, "script");
        Objects.requireNonNull(capabilityProfile, "capabilityProfile");
        Objects.requireNonNull(policyRevision, "policyRevision");
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0");
        }
        if (maxHits <= 0) {
            throw new IllegalArgumentException("maxHits must be > 0");
        }
        requestedBy = requireText(requestedBy, "requestedBy");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
