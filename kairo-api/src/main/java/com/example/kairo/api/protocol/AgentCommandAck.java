package com.example.kairo.api.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * The authoritative wire contract for an Agent's ack of a dispatched command
 * ({@code POST /api/v1/agent-commands/{id}/ack}, V1.7 M0 / frozen plan &sect;3.4). {@code status}
 * is {@code ACKED} or {@code FAILED}; {@code result} carries the command result object;
 * {@code errorMessage} carries the failure reason. This DTO owns the ack wire shape so it can be
 * frozen and diffed; the platform's ack handler and this DTO must stay wire-compatible.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentCommandAck(
        @JsonProperty("status") String status,
        @JsonProperty("result") Map<String, Object> result,
        @JsonProperty("errorMessage") String errorMessage,
        @JsonProperty("reason") String reason) {

    /** A representative successful V1.6 ack used to freeze the wire JSON shape. */
    public static AgentCommandAck ackedRepresentative() {
        return new AgentCommandAck("ACKED", Map.of("revision", 7, "hash", "abc123"), null,
                "agent command applied");
    }

    public static AgentCommandAck failedRepresentative() {
        return new AgentCommandAck("FAILED", null, "script compilation failed",
                "agent command failed");
    }

    /**
     * V1.6's capability-rejection path carries both a structured result and a human-readable
     * error.  Keeping a separate witness prevents {@link JsonInclude} from hiding the fact that
     * all four properties are part of the wire contract.
     */
    public static AgentCommandAck capabilityFailureRepresentative() {
        return new AgentCommandAck("FAILED", Map.of(
                "code", "CAPABILITY_NOT_SUPPORTED",
                "category", "CAPABILITY",
                "commandType", "APPLY_RULE",
                "retryable", false),
                "command capability is not advertised", "capability not supported");
    }
}
