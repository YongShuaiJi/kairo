package com.example.kairo.api;

import java.util.List;
import java.util.Objects;

/**
 * The result of statically analyzing a rule chain for conflicts.
 *
 * <p>A report is immutable and carries zero or more {@link ConflictFinding}s.
 * {@link #hasBlocking()} is true when at least one {@link ConflictSeverity#ERROR}
 * finding is present &mdash; the Platform must not dispatch such a chain.
 * {@link ConflictSeverity#POTENTIAL} findings do not block but require explicit
 * user confirmation, which the caller records separately.
 */
public record ConflictReport(List<ConflictFinding> findings) {

    private static final ConflictReport EMPTY = new ConflictReport(List.of());

    public ConflictReport {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static ConflictReport empty() {
        return EMPTY;
    }

    public static ConflictReport of(ConflictFinding... findings) {
        return new ConflictReport(List.of(findings));
    }

    public boolean hasFindings() {
        return !findings.isEmpty();
    }

    public boolean hasBlocking() {
        return findings.stream().anyMatch(ConflictFinding::isBlocking);
    }

    public boolean hasPotential() {
        return findings.stream().anyMatch(f -> f.severity() == ConflictSeverity.POTENTIAL);
    }

    public List<ConflictFinding> blocking() {
        return findings.stream().filter(ConflictFinding::isBlocking).toList();
    }

    public List<ConflictFinding> potential() {
        return findings.stream().filter(f -> f.severity() == ConflictSeverity.POTENTIAL).toList();
    }

    public ConflictReport merge(ConflictReport other) {
        Objects.requireNonNull(other, "other");
        if (other.findings.isEmpty()) {
            return this;
        }
        if (this.findings.isEmpty()) {
            return other;
        }
        List<ConflictFinding> merged = new java.util.ArrayList<>(this.findings);
        merged.addAll(other.findings);
        return new ConflictReport(merged);
    }
}
