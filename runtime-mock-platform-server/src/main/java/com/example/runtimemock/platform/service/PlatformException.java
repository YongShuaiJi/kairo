package com.example.runtimemock.platform.service;

import java.util.Map;

public final class PlatformException extends RuntimeException {

    private final int status;
    private final String code;
    private final boolean retryable;
    private final Map<String, Object> details;

    private PlatformException(int status, String code, String message, boolean retryable,
                              Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = details;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static PlatformException badRequest(String code, String message) {
        return new PlatformException(400, code, message, false, Map.of());
    }

    public static PlatformException forbidden(String capability) {
        return new PlatformException(403, "FORBIDDEN",
                "Missing required capability: " + capability, false, Map.of("capability", capability));
    }

    public static PlatformException notFound(String resourceType, String resourceId) {
        return new PlatformException(404, "RESOURCE_NOT_FOUND",
                resourceType + " not found: " + resourceId, false,
                Map.of("resourceType", resourceType, "resourceId", resourceId));
    }

    public static PlatformException conflict(String code, String message, Map<String, Object> details) {
        return new PlatformException(409, code, message, true, details);
    }
}
