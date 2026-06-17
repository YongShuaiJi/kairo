package com.example.runtimemock.platform.service;

public record RequestContext(
        String actor,
        String correlationId,
        String ipAddress,
        String identitySource
) {
}
