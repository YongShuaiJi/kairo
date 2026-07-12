package com.example.kairo.platform.command;

import java.util.Map;

/**
 * Thrown by {@link TargetResolutionExchange#await} when the agent explicitly acked a
 * {@code RESOLVE_TARGET} command as FAILED. Carries the agent's result map so the caller can
 * surface structured diagnostics rather than a generic message.
 */
public class TargetResolutionFailure extends RuntimeException {
    private final Map<String, Object> result;

    public TargetResolutionFailure(String message, Map<String, Object> result) {
        super(message);
        this.result = result == null ? Map.of() : result;
    }

    public Map<String, Object> result() {
        return result;
    }
}
