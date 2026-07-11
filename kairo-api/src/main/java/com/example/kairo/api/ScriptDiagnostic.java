package com.example.kairo.api;

import java.util.Objects;

/** Structured, machine-readable script diagnostic. */
public record ScriptDiagnostic(
        Phase phase,
        Severity severity,
        int line,
        int column,
        String code,
        String message,
        String targetClassLoaderId,
        String suggestion
) {
    public ScriptDiagnostic {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(severity, "severity");
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("line and column must be >= 0");
        }
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        targetClassLoaderId = optionalText(targetClassLoaderId, "targetClassLoaderId");
        suggestion = optionalText(suggestion, "suggestion");
    }

    private static String optionalText(String value, String name) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must be null or non-blank");
        }
        return value;
    }

    public enum Phase { VALIDATION, COMPILATION, EXECUTION }

    public enum Severity { INFO, WARNING, ERROR }
}
