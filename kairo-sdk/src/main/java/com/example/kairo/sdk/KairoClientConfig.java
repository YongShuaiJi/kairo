package com.example.kairo.sdk;

import java.time.Duration;

/**
 * Configuration for a {@link KairoClient} (V1.6 §5.4). The token is the caller's
 * own least-privilege Platform API token; the SDK never stores or accepts a
 * super-admin token on behalf of an MCP/AI caller.
 */
public final class KairoClientConfig {

    private String baseUrl;
    private String token;
    private Duration timeout = Duration.ofSeconds(30);
    private String source = "sdk";
    private String correlationId;

    public KairoClientConfig(String baseUrl, String token) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.token = token == null ? "" : token;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String token() {
        return token;
    }

    public Duration timeout() {
        return timeout;
    }

    public KairoClientConfig timeout(Duration timeout) {
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        return this;
    }

    /** Caller source: {@code web}, {@code cli}, {@code sdk}, {@code mcp}. */
    public String source() {
        return source;
    }

    public KairoClientConfig source(String source) {
        this.source = source == null || source.isBlank() ? "sdk" : source;
        return this;
    }

    public String correlationId() {
        return correlationId;
    }

    public KairoClientConfig correlationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }
}
