package com.example.kairo.platform.script;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptPolicyRevision;
import com.example.kairo.api.ScriptSessionStatus;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * Persisted row for one temporary script session.
 *
 * <p>Mirrors the agent-side {@code ScriptSession} state machine but is owned by the platform: the
 * platform computes the effective tier, pins the policy revision/hash, dispatches agent commands and
 * reconciles the persisted status from each ack. {@code version} is the optimistic-lock token; every
 * transition updates exactly one row by {@code (id, version)} and bumps the version. The script
 * source itself is never stored &mdash; only {@code scriptHash} &mdash; so the audit trail cannot leak
 * trial script bodies.
 */
public record ScriptSessionRecord(
        String id,
        String agentId,
        String applicationId,
        String targetClassName,
        String targetClassLoaderId,
        String targetMethodName,
        String targetMethodDescriptor,
        String scriptHash,
        CapabilityProfile requestedProfile,
        CapabilityProfile effectiveProfile,
        CapabilityProfile platformMaxProfile,
        CapabilityProfile applicationMaxProfile,
        long policyRevision,
        String policyHash,
        long ttlMillis,
        long maxHits,
        ScriptSessionStatus status,
        long hitCount,
        long version,
        String idempotencyKey,
        String requestedBy,
        String formalRuleId,
        String agentResultJson,
        String diagnosticsJson,
        Timestamp createdAt,
        Timestamp expiresAt,
        Timestamp appliedAt,
        Timestamp revertedAt,
        Timestamp updatedAt,
        String createdBy,
        String correlationId
) {
    public ScriptSessionRecord {
        id = requireText(id, "id");
        agentId = requireText(agentId, "agentId");
        applicationId = requireText(applicationId, "applicationId");
        targetClassName = requireText(targetClassName, "targetClassName");
        targetMethodName = requireText(targetMethodName, "targetMethodName");
        targetMethodDescriptor = requireText(targetMethodDescriptor, "targetMethodDescriptor");
        scriptHash = requireText(scriptHash, "scriptHash");
        Objects.requireNonNull(requestedProfile, "requestedProfile");
        Objects.requireNonNull(effectiveProfile, "effectiveProfile");
        Objects.requireNonNull(platformMaxProfile, "platformMaxProfile");
        Objects.requireNonNull(applicationMaxProfile, "applicationMaxProfile");
        Objects.requireNonNull(status, "status");
        if (policyRevision < 0) {
            throw new IllegalArgumentException("policyRevision must be >= 0");
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("ttlMillis must be > 0");
        }
        if (maxHits <= 0) {
            throw new IllegalArgumentException("maxHits must be > 0");
        }
        if (hitCount < 0) {
            throw new IllegalArgumentException("hitCount must be >= 0");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        requestedBy = requireText(requestedBy, "requestedBy");
        agentResultJson = agentResultJson == null ? "{}" : agentResultJson;
        diagnosticsJson = diagnosticsJson == null ? "[]" : diagnosticsJson;
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        createdBy = requireText(createdBy, "createdBy");
        correlationId = correlationId == null ? "" : correlationId;
    }

    public ScriptPolicyRevision toPolicyRevision() {
        return new ScriptPolicyRevision(policyRevision, policyHash);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
