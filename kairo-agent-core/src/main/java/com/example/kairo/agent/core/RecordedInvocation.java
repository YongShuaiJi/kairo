package com.example.kairo.agent.core;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RecordedInvocation(
        String sessionId,
        String id,
        String traceId,
        String protocol,
        Instant eventTime,
        Map<String, Object> metadata,
        List<Object> arguments,
        Object result,
        Map<String, Object> error
) {
}
