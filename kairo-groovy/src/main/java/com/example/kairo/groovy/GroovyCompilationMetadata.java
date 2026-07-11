package com.example.kairo.groovy;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptPolicyRevision;

import java.util.Objects;

/**
 * Immutable metadata captured for one compiled Groovy script.
 *
 * <p>Includes the SHA-256 of the source, the capability profile and policy revision under
 * which it was compiled, the Groovy compiler version, the stable id of the target
 * ClassLoader used to resolve business classes, and the compiled artifact size in bytes.
 */
public record GroovyCompilationMetadata(
        String scriptHash,
        CapabilityProfile profile,
        ScriptPolicyRevision policyRevision,
        String groovyVersion,
        String targetClassLoaderId,
        int artifactBytes
) {
    public GroovyCompilationMetadata {
        scriptHash = requireText(scriptHash, "scriptHash");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(policyRevision, "policyRevision");
        groovyVersion = requireText(groovyVersion, "groovyVersion");
        targetClassLoaderId = requireText(targetClassLoaderId, "targetClassLoaderId");
        if (artifactBytes < 0) {
            throw new IllegalArgumentException("artifactBytes must be >= 0");
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
