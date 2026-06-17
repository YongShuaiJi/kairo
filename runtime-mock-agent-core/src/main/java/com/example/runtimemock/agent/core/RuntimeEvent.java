package com.example.runtimemock.agent.core;

public record RuntimeEvent(
        long timestamp,
        String type,
        String actor,
        String ruleId,
        String target,
        String message
) {
}
