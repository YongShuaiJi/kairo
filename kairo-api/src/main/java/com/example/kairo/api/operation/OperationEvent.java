package com.example.kairo.api.operation;

import java.util.Map;
import java.util.Objects;

/**
 * One event in an {@link Operation}'s lifecycle (V1.6 &sect;5.1 {@code operation_event}).
 * Events form an append-only stream queryable via {@code GET /operations/{id}/events}.
 */
public record OperationEvent(
        String operationId,
        long sequence,
        String type,
        long occurredAt,
        String actor,
        Map<String, Object> detail
) {
    public OperationEvent {
        operationId = Objects.requireNonNull(operationId, "operationId");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must be >= 0");
        }
        type = Objects.requireNonNull(type, "type");
        if (occurredAt < 0) {
            throw new IllegalArgumentException("occurredAt must be >= 0");
        }
        actor = actor == null ? "" : actor;
        detail = detail == null ? Map.of() : Map.copyOf(detail);
    }
}
