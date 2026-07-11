package com.example.kairo.api;

import java.util.Objects;

/** Complete, explicit context for compiling one script. */
public record ScriptCompilationRequest(
        String script,
        String scriptHash,
        CapabilityProfile capabilityProfile,
        ScriptPolicyRevision policyRevision,
        String targetClassLoaderId
) {
    public ScriptCompilationRequest {
        script = requireText(script, "script");
        scriptHash = requireText(scriptHash, "scriptHash");
        Objects.requireNonNull(capabilityProfile, "capabilityProfile");
        Objects.requireNonNull(policyRevision, "policyRevision");
        targetClassLoaderId = requireText(targetClassLoaderId, "targetClassLoaderId");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
