package com.example.kairo.api.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * The authoritative wire contract for a command the Platform dispatches to an Agent when it polls
 * {@code POST /api/v1/agents/{id}/commands/next} (V1.7 M0 / frozen plan &sect;3.4). The JSON field
 * names match the V1.6 wire exactly (snake_case, as emitted by the platform's command service);
 * this DTO owns that contract so it can be frozen and diffed. The platform's live poll response and
 * this DTO must stay wire-compatible.
 *
 * <p>{@code payload} is the resolved/enriched command payload object; {@code payload_json} /
 * {@code result_json} are the persisted JSON strings. {@code idempotency_key}, {@code correlation_id}
 * and the version/order fields are the idempotency and ordering contract the agent relies on.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCommandEnvelope(
        @JsonProperty("id") String id,
        @JsonProperty("agent_id") String agentId,
        @JsonProperty("command_type") String commandType,
        @JsonProperty("status") String status,
        @JsonProperty("idempotency_key") String idempotencyKey,
        @JsonProperty("payload_json") String payloadJson,
        @JsonProperty("result_json") String resultJson,
        @JsonProperty("attempts") int attempts,
        @JsonProperty("max_attempts") int maxAttempts,
        @JsonProperty("available_at") String availableAt,
        @JsonProperty("lease_expires_at") String leaseExpiresAt,
        @JsonProperty("dispatched_at") String dispatchedAt,
        @JsonProperty("completed_at") String completedAt,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("created_by") String createdBy,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt,
        @JsonProperty("correlation_id") String correlationId,
        @JsonProperty("rollback_execution_id") String rollbackExecutionId,
        @JsonProperty("expected_revision") Long expectedRevision,
        @JsonProperty("desired_revision") Long desiredRevision,
        @JsonProperty("desired_hash") String desiredHash,
        @JsonProperty("result_hash") String resultHash,
        @JsonProperty("payload") Map<String, Object> payload) {

    /**
     * A full-shape witness used to freeze every V1.6 wire property. Values are deliberately
     * non-null, including mutually uncommon lifecycle fields, so {@link JsonInclude} cannot hide
     * an optional property and make the freeze test pass without covering it.
     */
    public static AgentCommandEnvelope representative() {
        return new AgentCommandEnvelope(
                "cmd-v17-freeze", "agent-v17", "APPLY_RULE", "DISPATCHED", "idem-v17-freeze",
                "{\"commandType\":\"APPLY_RULE\"}", "{}", 1, 5,
                "2026-07-15T00:00:00Z", "2026-07-15T00:01:00Z", "2026-07-15T00:00:05Z",
                "2026-07-15T00:02:00Z", "representative error", "system",
                "2026-07-15T00:00:00Z", "2026-07-15T00:00:05Z", "corr-v17-freeze",
                "rollback-v17-freeze", 6L, 7L, "desired-hash-v17", "result-hash-v17",
                Map.of("commandType", "APPLY_RULE", "protocolVersion", "v1"));
    }
}
