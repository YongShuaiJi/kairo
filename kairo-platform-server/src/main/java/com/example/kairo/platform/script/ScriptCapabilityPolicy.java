package com.example.kairo.platform.script;

import java.sql.Timestamp;
import java.util.Objects;

/**
 * Persisted capability policy for one scope.
 *
 * <p>The platform-level ceiling is stored with {@code scope = PLATFORM} and the sentinel application
 * id {@code __platform__}; per-application ceilings use {@code scope = APPLICATION} and the real
 * application id. {@code revision} is monotonic and doubles as the optimistic-lock token and the
 * agent compile-cache invalidation key; {@code policyHash} is the stable hash of the effective policy
 * content so a ceiling change always yields a new cache key even when the revision source differs.
 */
public record ScriptCapabilityPolicy(
        String scope,
        String applicationId,
        String allowedMaxProfile,
        long revision,
        String policyHash,
        String modifiedBy,
        Timestamp createdAt,
        Timestamp updatedAt
) {
    public static final String PLATFORM = "PLATFORM";
    public static final String APPLICATION = "APPLICATION";
    public static final String PLATFORM_APPLICATION_ID = "__platform__";

    public ScriptCapabilityPolicy {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(applicationId, "applicationId");
        allowedMaxProfile = requireProfile(allowedMaxProfile);
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        if (policyHash == null || policyHash.isBlank()) {
            throw new IllegalArgumentException("policyHash must not be blank");
        }
        modifiedBy = requireText(modifiedBy, "modifiedBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public static String requireProfile(String value) {
        return switch (value) {
            case "SAFE", "EXTENDED", "UNRESTRICTED" -> value;
            default -> throw new IllegalArgumentException("allowedMaxProfile must be SAFE, EXTENDED or UNRESTRICTED");
        };
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public boolean isPlatform() {
        return PLATFORM.equals(scope);
    }
}
