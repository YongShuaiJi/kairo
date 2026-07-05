package com.example.kairo.platform.service;

public record RequestContext(
        String actor,
        String correlationId,
        String ipAddress,
        String identitySource,
        String device
) {
    public RequestContext(String actor, String correlationId, String ipAddress, String identitySource) {
        this(actor, correlationId, ipAddress, identitySource, "");
    }
}
