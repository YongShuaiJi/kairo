package com.example.kairo.platform.command;

import java.util.Map;

/**
 * Thrown by {@link ScriptSessionExchange#await} when the agent explicitly acked a script command as
 * FAILED. Carries the agent's result map (which holds structured diagnostics) so the caller can
 * surface them rather than a generic message.
 */
public class ScriptCommandFailure extends RuntimeException {
    private final Map<String, Object> result;

    public ScriptCommandFailure(String message, Map<String, Object> result) {
        super(message);
        this.result = result == null ? Map.of() : result;
    }

    public Map<String, Object> result() {
        return result;
    }
}
