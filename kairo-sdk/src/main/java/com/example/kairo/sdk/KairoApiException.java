package com.example.kairo.sdk;

/**
 * Raised when the Platform API returns a non-2xx response. Carries the frozen
 * V1.6 {@link com.example.kairo.api.error.ApiError} so SDK/CLI/MCP clients can
 * branch on {@code code}/{@code category} instead of parsing the message.
 */
public final class KairoApiException extends RuntimeException {

    private final int status;
    private final com.example.kairo.api.error.ApiError error;

    public KairoApiException(int status, com.example.kairo.api.error.ApiError error) {
        super(error == null ? "HTTP " + status : error.message());
        this.status = status;
        this.error = error;
    }

    public int status() {
        return status;
    }

    public com.example.kairo.api.error.ApiError error() {
        return error;
    }

    public String code() {
        return error == null ? "INTERNAL_ERROR" : error.code();
    }
}
