package com.example.runtimemock.control;

import java.util.Map;

final class ControlPlaneException extends RuntimeException {

    private final int status;
    private final String code;
    private final boolean retryable;
    private final Map<String, Object> details;

    private ControlPlaneException(int status, String code, String message,
                                  boolean retryable, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryable = retryable;
        this.details = details;
    }

    int status() {
        return status;
    }

    String code() {
        return code;
    }

    boolean retryable() {
        return retryable;
    }

    Map<String, Object> details() {
        return details;
    }

    static ControlPlaneException badRequest(String code, String message) {
        return new ControlPlaneException(400, code, message, false, Map.of());
    }

    static ControlPlaneException notFound(String resourceType, String resourceId) {
        return new ControlPlaneException(404, "RESOURCE_NOT_FOUND",
                resourceType + " not found: " + resourceId, false,
                Map.of("resourceType", resourceType, "resourceId", resourceId));
    }

    static ControlPlaneException conflict(String code, String message, Map<String, Object> details) {
        return new ControlPlaneException(409, code, message, true, details);
    }
}
