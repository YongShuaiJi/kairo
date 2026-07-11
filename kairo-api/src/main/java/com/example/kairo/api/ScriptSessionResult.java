package com.example.kairo.api;

import java.util.List;
import java.util.Objects;

/** Immutable current result of a temporary script session. */
public record ScriptSessionResult(
        String sessionId,
        ScriptSessionStatus status,
        long createdAt,
        long expiresAt,
        long hitCount,
        List<ScriptDiagnostic> diagnostics
) {
    public ScriptSessionResult {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        Objects.requireNonNull(status, "status");
        if (createdAt < 0 || expiresAt <= createdAt) {
            throw new IllegalArgumentException("timestamps must satisfy 0 <= createdAt < expiresAt");
        }
        if (hitCount < 0) {
            throw new IllegalArgumentException("hitCount must be >= 0");
        }
        Objects.requireNonNull(diagnostics, "diagnostics");
        diagnostics = List.copyOf(diagnostics);
    }
}
