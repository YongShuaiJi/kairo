package com.example.runtimemock.platform.api;

import java.util.Map;

public record ApiError(
        String code,
        String message,
        String correlationId,
        Map<String, Object> details,
        boolean retryable
) {
}
