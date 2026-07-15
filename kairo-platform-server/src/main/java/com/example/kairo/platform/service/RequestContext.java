package com.example.kairo.platform.service;

/**
 * Request-scoped identity context (V1.6). Carries the actor, correlation id,
 * origin metadata and the optional {@link TokenScope} bound to the API token.
 */
public record RequestContext(
        String actor,
        String correlationId,
        String ipAddress,
        String identitySource,
        String device,
        TokenScope tokenScope
) {
    public RequestContext(String actor, String correlationId, String ipAddress,
                          String identitySource, String device, TokenScope tokenScope) {
        this.actor = actor;
        this.correlationId = correlationId == null ? "" : correlationId;
        this.ipAddress = ipAddress == null ? "" : ipAddress;
        this.identitySource = identitySource == null ? "" : identitySource;
        this.device = device == null ? "" : device;
        this.tokenScope = tokenScope;
    }

    public RequestContext(String actor, String correlationId, String ipAddress,
                          String identitySource, String device) {
        this(actor, correlationId, ipAddress, identitySource, device, null);
    }

    public RequestContext(String actor, String correlationId, String ipAddress, String identitySource) {
        this(actor, correlationId, ipAddress, identitySource, "", null);
    }

    /** The token id, or null when authenticated via a non-token mechanism. */
    public String tokenId() {
        return tokenScope == null ? null : tokenScope.tokenId();
    }

    /** The caller source, or null when unknown. */
    public String source() {
        return tokenScope == null ? null : tokenScope.source();
    }
}
