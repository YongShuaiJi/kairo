package com.example.runtimemock.platform.event;

public record OutboxEvent(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        int attempts
) {
    public String key() {
        return aggregateType + ":" + aggregateId;
    }
}
