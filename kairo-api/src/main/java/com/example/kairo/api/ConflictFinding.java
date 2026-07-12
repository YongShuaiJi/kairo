package com.example.kairo.api;

import java.util.List;
import java.util.Objects;

/**
 * One static conflict finding produced by the conflict analyzer.
 *
 * @param kind     the {@link ConflictKind}
 * @param severity the {@link ConflictSeverity}
 * @param ruleIds  the rule ids involved in the finding
 * @param message  human-readable explanation
 */
public record ConflictFinding(ConflictKind kind, ConflictSeverity severity,
                              List<String> ruleIds, String message) {

    public ConflictFinding {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        ruleIds = ruleIds == null ? List.of() : List.copyOf(ruleIds);
    }

    public boolean isBlocking() {
        return severity == ConflictSeverity.ERROR;
    }

    public static ConflictFinding of(ConflictKind kind, ConflictSeverity severity, String message, String... ruleIds) {
        return new ConflictFinding(kind, severity, List.of(ruleIds), message);
    }
}
