package com.example.kairo.groovy;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptPolicyRevision;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Complete, immutable context for compiling one Groovy script under a chosen capability
 * tier. Carries everything {@link GroovyScriptCompiler} needs to select a policy, resolve
 * business classes, and enforce the tier-shared size limits.
 *
 * <p>The {@code targetClassLoaderId} is stable for a given {@code targetClassLoader}
 * instance (see {@link TargetClassLoaderIds}). Callers that already hold a canonical id
 * from the agent layer may supply it explicitly to stay consistent across modules;
 * otherwise it is derived.
 */
public final class ScriptCompilationContext {

    /** Default maximum script size in UTF-8 bytes, shared by every tier. */
    public static final int DEFAULT_MAX_SCRIPT_BYTES = 16 * 1024;

    /** Default maximum compiled artifact size in bytes, shared by every tier. */
    public static final int DEFAULT_MAX_ARTIFACT_BYTES = 512 * 1024;

    /** Revision reported for the legacy SAFE compile path that has no explicit revision. */
    public static final ScriptPolicyRevision DEFAULT_SAFE_REVISION =
            new ScriptPolicyRevision(0, "safe-default");

    private final CapabilityProfile profile;
    private final ScriptPolicyRevision policyRevision;
    private final ClassLoader targetClassLoader;
    private final String targetClassLoaderId;
    private final Set<String> allowedPackages;
    private final Set<String> allowedClasses;
    private final int maxScriptBytes;
    private final int maxArtifactBytes;

    private ScriptCompilationContext(Builder builder) {
        this.profile = Objects.requireNonNull(builder.profile, "profile");
        this.policyRevision = Objects.requireNonNull(builder.policyRevision, "policyRevision");
        this.targetClassLoader = Objects.requireNonNull(builder.targetClassLoader, "targetClassLoader");
        this.targetClassLoaderId = resolveClassLoaderId(builder);
        this.allowedPackages = immutableCopy(builder.allowedPackages);
        this.allowedClasses = immutableCopy(builder.allowedClasses);
        this.maxScriptBytes = requirePositive(builder.maxScriptBytes, "maxScriptBytes");
        this.maxArtifactBytes = requirePositive(builder.maxArtifactBytes, "maxArtifactBytes");
    }

    public CapabilityProfile profile() {
        return profile;
    }

    public ScriptPolicyRevision policyRevision() {
        return policyRevision;
    }

    public ClassLoader targetClassLoader() {
        return targetClassLoader;
    }

    public String targetClassLoaderId() {
        return targetClassLoaderId;
    }

    public Set<String> allowedPackages() {
        return allowedPackages;
    }

    public Set<String> allowedClasses() {
        return allowedClasses;
    }

    public int maxScriptBytes() {
        return maxScriptBytes;
    }

    public int maxArtifactBytes() {
        return maxArtifactBytes;
    }

    /** Enforce the shared maximum script byte size. */
    public void enforceScriptSize(String script) {
        Objects.requireNonNull(script, "script");
        int bytes = script.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxScriptBytes) {
            throw new IllegalArgumentException("Groovy script is too large: " + bytes
                    + " bytes, max " + maxScriptBytes);
        }
    }

    /** Enforce the shared maximum compiled artifact byte size. */
    public void enforceArtifactSize(int artifactBytes) {
        if (artifactBytes > maxArtifactBytes) {
            throw new IllegalArgumentException("Groovy compiled artifact is too large: " + artifactBytes
                    + " bytes, max " + maxArtifactBytes);
        }
    }

    /** A fresh builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** Convenience for the legacy SAFE compile path. */
    public static ScriptCompilationContext safeDefaults(ClassLoader targetClassLoader) {
        return builder()
                .profile(CapabilityProfile.SAFE)
                .policyRevision(DEFAULT_SAFE_REVISION)
                .targetClassLoader(targetClassLoader)
                .build();
    }

    private static String resolveClassLoaderId(Builder builder) {
        if (builder.targetClassLoaderId != null && !builder.targetClassLoaderId.isBlank()) {
            return builder.targetClassLoaderId;
        }
        return TargetClassLoaderIds.idOf(builder.targetClassLoader);
    }

    private static Set<String> immutableCopy(Set<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return Set.of();
        }
        Set<String> copy = new LinkedHashSet<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                throw new IllegalArgumentException("allowed entries must not be blank");
            }
            copy.add(entry.trim());
        }
        return Set.copyOf(copy);
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return value;
    }

    public static final class Builder {
        private CapabilityProfile profile;
        private ScriptPolicyRevision policyRevision;
        private ClassLoader targetClassLoader;
        private String targetClassLoaderId;
        private Set<String> allowedPackages = Set.of();
        private Set<String> allowedClasses = Set.of();
        private int maxScriptBytes = DEFAULT_MAX_SCRIPT_BYTES;
        private int maxArtifactBytes = DEFAULT_MAX_ARTIFACT_BYTES;

        private Builder() {
        }

        public Builder profile(CapabilityProfile profile) {
            this.profile = profile;
            return this;
        }

        public Builder policyRevision(ScriptPolicyRevision policyRevision) {
            this.policyRevision = policyRevision;
            return this;
        }

        public Builder targetClassLoader(ClassLoader targetClassLoader) {
            this.targetClassLoader = targetClassLoader;
            return this;
        }

        /** Optional explicit stable id; derived from the loader when not set. */
        public Builder targetClassLoaderId(String targetClassLoaderId) {
            this.targetClassLoaderId = targetClassLoaderId;
            return this;
        }

        public Builder allowedPackages(Set<String> allowedPackages) {
            this.allowedPackages = allowedPackages;
            return this;
        }

        public Builder allowedClasses(Set<String> allowedClasses) {
            this.allowedClasses = allowedClasses;
            return this;
        }

        public Builder maxScriptBytes(int maxScriptBytes) {
            this.maxScriptBytes = maxScriptBytes;
            return this;
        }

        public Builder maxArtifactBytes(int maxArtifactBytes) {
            this.maxArtifactBytes = maxArtifactBytes;
            return this;
        }

        public ScriptCompilationContext build() {
            return new ScriptCompilationContext(this);
        }
    }
}
