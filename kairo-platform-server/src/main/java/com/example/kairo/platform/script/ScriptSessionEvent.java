package com.example.kairo.platform.script;

import java.sql.Timestamp;
import java.util.Objects;

/** One state-change entry in a session's history; complements the unified audit event log. */
public record ScriptSessionEvent(
        String id,
        String sessionId,
        String action,
        String fromStatus,
        String toStatus,
        String actor,
        String detail,
        String commandId,
        Timestamp createdAt
) {
    public ScriptSessionEvent {
        id = requireText(id, "id");
        sessionId = requireText(sessionId, "sessionId");
        action = requireText(action, "action");
        toStatus = requireText(toStatus, "toStatus");
        actor = requireText(actor, "actor");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
