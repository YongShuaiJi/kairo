package com.example.kairo.api;

import java.util.List;
import java.util.Objects;

/** Metadata and diagnostics produced by one compilation attempt. */
public record ScriptCompilationResult(
        boolean successful,
        String scriptHash,
        CapabilityProfile capabilityProfile,
        ScriptPolicyRevision policyRevision,
        String compilerVersion,
        String targetClassLoaderId,
        List<ScriptDiagnostic> diagnostics
) {
    public ScriptCompilationResult {
        scriptHash = requireText(scriptHash, "scriptHash");
        Objects.requireNonNull(capabilityProfile, "capabilityProfile");
        Objects.requireNonNull(policyRevision, "policyRevision");
        compilerVersion = requireText(compilerVersion, "compilerVersion");
        targetClassLoaderId = requireText(targetClassLoaderId, "targetClassLoaderId");
        Objects.requireNonNull(diagnostics, "diagnostics");
        diagnostics = List.copyOf(diagnostics);
        if (successful && diagnostics.stream().anyMatch(d -> d.severity() == ScriptDiagnostic.Severity.ERROR)) {
            throw new IllegalArgumentException("successful result must not contain error diagnostics");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
