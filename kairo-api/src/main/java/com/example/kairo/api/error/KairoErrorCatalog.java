package com.example.kairo.api.error;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The authoritative, production error-code catalog for the V1 API surface (V1.7 M0 / frozen plan
 * &sect;3.1). Every {@code ApiError.code} the platform emits is registered here with its stable
 * {@link ErrorCategory}, HTTP status and {@code retryable} flag -- the exact values V1.6 emits
 * (commit {@code 113823b}). {@code PlatformException} and the error handler resolve or validate
 * against this catalog: an unknown, reused-with-different-metadata, or mismatched code fails fast
 * rather than silently emitting an undocumented contract.
 *
 * <p>This catalog is the single source of truth that replaces regex-based code discovery. It is
 * itself a frozen contract surface: removing or reclassifying an entry is a breaking change
 * guarded by {@code ErrorCodeFreezeTest}.
 */
public final class KairoErrorCatalog {

    /** One registered error code with its stable V1 metadata. */
    public record Entry(String code, ErrorCategory category, int httpStatus, boolean retryable) {
        public Entry {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(category, "category");
            if (code.isBlank()) {
                throw new IllegalArgumentException("code must not be blank");
            }
        }
    }

    private static final Map<String, Entry> BY_CODE = build();

    private KairoErrorCatalog() {
    }

    /** Resolve a code to its catalog entry, or {@code null} if it is not registered. */
    public static Entry resolve(String code) {
        if (code == null) {
            return null;
        }
        return BY_CODE.get(code);
    }

    /** Resolve a code, failing fast if it is not registered (unknown error code). */
    public static Entry require(String code) {
        Entry entry = resolve(code);
        if (entry == null) {
            throw new IllegalStateException("Unknown/unregistered error code not in catalog: " + code
                    + " -- register it in KairoErrorCatalog or fix the caller.");
        }
        return entry;
    }

    /**
     * Every registered entry (the authoritative V1 error contract), sorted by code for
     * deterministic output.
     */
    public static Set<Entry> entries() {
        java.util.Set<Entry> sorted = new java.util.TreeSet<>(
                java.util.Comparator.comparing(Entry::code));
        sorted.addAll(BY_CODE.values());
        return java.util.Collections.unmodifiableSet(sorted);
    }

    /** Every registered code, sorted for deterministic output. */
    public static Set<String> codes() {
        java.util.Set<String> sorted = new java.util.TreeSet<>(BY_CODE.keySet());
        return java.util.Collections.unmodifiableSet(sorted);
    }

    private static Map<String, Entry> build() {
        Map<String, Entry> m = new LinkedHashMap<>();
        // badRequest -> 400 / VALIDATION / not-retryable
        for (String c : List.of(
                "ATTACH_TARGET_REQUIRED", "BYTECODE_TOO_LARGE", "CANNOT_DELETE_SUPER_ADMIN",
                "CANNOT_RENEW_SELF_TOKEN", "CLIENT_DELIVERY_ID_DISABLED", "CLIENT_RULE_ID_DISABLED",
                "FIELD_REQUIRED", "INVALID_COMMAND_STATUS", "INVALID_ENVIRONMENT_TYPE",
                "INVALID_ENVIRONMENT", "INVALID_FIELD", "INVALID_MAX_HITS", "INVALID_PHASE",
                "INVALID_RESOURCE_TYPE", "INVALID_REVISION", "INVALID_ROLLOUT_RESOURCE",
                "INVALID_ROLLOUT_VERSION", "INVALID_RULE_STATUS", "INVALID_SUBJECT_TYPE",
                "INVALID_TOKEN_EXPIRES_AT", "INVALID_TOKEN_MAX_SESSIONS", "INVALID_TOKEN_SCOPE",
                "INVALID_TOKEN_TTL", "INVALID_TTL", "MISSING_FIELD", "RULE_DISABLED",
                "TARGET_REJECTED", "VALIDATION_FAILED")) {
            register(m, c, ErrorCategory.VALIDATION, 400, false);
        }
        // conflict -> 409 / CONFLICT / retryable
        for (String c : List.of(
                "AGENT_COMMAND_STATE_CONFLICT", "AGENT_NOT_REGISTERED", "AMBIGUOUS_TARGET",
                "ATTACH_EXECUTOR_REQUIRED", "AUTOMATION_SESSION_LIMIT_EXCEEDED",
                "AUTOMATION_SESSION_TERMINAL", "BUSINESS_ID_SEQUENCE_FAILED",
                "BYTECODE_DIAGNOSTIC_TIMEOUT", "BYTECODE_RESULT_MISSING", "CALL_SITE_SCAN_FAILED",
                "CALL_SITE_SCAN_TIMEOUT", "FENCING_SEQUENCE_FAILED", "FENCING_TOKEN_INVALID",
                "INSTANCE_NICKNAME_CONFLICT", "LIST_LOADERS_FAILED", "LIST_LOADERS_TIMEOUT",
                "NO_ACTIVE_ROLLOUT_AGENT", "OPERATION_PLAN_INVALID_TRANSITION",
                "OPERATION_PLAN_NOT_UNLOADABLE", "REDIS_FENCING_FAILED", "REDIS_SEQUENCE_FAILED",
                "REDIS_UNAVAILABLE", "RESOURCE_VERSION_CONFLICT", "RULE_TARGET_CLASS_MISSING",
                "RULE_TARGET_NOT_FOUND", "RULE_VERSION_DELETE_ALL", "SCRIPT_COMMAND_DISPATCH_FAILED",
                "SCRIPT_SESSION_EXISTS", "SCRIPT_SESSION_INVALID_TRANSITION",
                "SCRIPT_SESSION_TARGET_BUSY", "TARGET_DRIFTED", "TARGET_NOT_FOUND",
                "TARGET_RESOLUTION_FAILED", "TARGET_RESOLUTION_TIMEOUT",
                "TARGET_RESOLUTION_UNAVAILABLE", "TARGET_RESOLUTION_UNEXPECTED",
                "USERNAME_CONFLICT")) {
            register(m, c, ErrorCategory.CONFLICT, 409, true);
        }
        // forbidden(code, ...) -> 403 / AUTHORIZATION / not-retryable
        for (String c : List.of("TOKEN_SCOPE_DENIED")) {
            register(m, c, ErrorCategory.AUTHORIZATION, 403, false);
        }
        // methodNotAllowed -> 405 / VALIDATION / not-retryable
        for (String c : List.of("RULE_AGGREGATE_LIFECYCLE_DISABLED", "RULE_MANUAL_DELETE_DISABLED",
                "RULE_VERSION_MANUAL_DELETE_DISABLED")) {
            register(m, c, ErrorCategory.VALIDATION, 405, false);
        }
        // unauthorized(code, ...) -> 401 / AUTHENTICATION / not-retryable
        for (String c : List.of("TOKEN_MAX_SESSIONS_INVALID", "TOKEN_SCOPE_INVALID")) {
            register(m, c, ErrorCategory.AUTHENTICATION, 401, false);
        }
        // Codes synthesized by the non-code factory overloads.
        register(m, "FORBIDDEN", ErrorCategory.AUTHORIZATION, 403, false);
        register(m, "UNAUTHORIZED", ErrorCategory.AUTHENTICATION, 401, false);
        register(m, "RESOURCE_NOT_FOUND", ErrorCategory.NOT_FOUND, 404, false);
        register(m, "CAPABILITY_NOT_SUPPORTED", ErrorCategory.CAPABILITY, 409, false);
        register(m, "PROTOCOL_VERSION_NOT_SUPPORTED", ErrorCategory.CAPABILITY, 409, false);
        // Direct ApiError.of codes emitted by the handler / filters (V1.6 baseline values).
        register(m, "ROUTE_NOT_FOUND", ErrorCategory.NOT_FOUND, 404, false);
        register(m, "IDEMPOTENCY_KEY_CONFLICT", ErrorCategory.CONFLICT, 409, false);
        register(m, "IDEMPOTENCY_KEY_IN_PROGRESS", ErrorCategory.CONFLICT, 409, true);
        // INTERNAL_ERROR is emitted directly via ApiError.of by the handler / automation service.
        register(m, "INTERNAL_ERROR", ErrorCategory.INTERNAL, 500, false);
        return Map.copyOf(m);
    }

    private static void register(Map<String, Entry> m, String code, ErrorCategory category,
                                 int httpStatus, boolean retryable) {
        Entry entry = new Entry(code, category, httpStatus, retryable);
        Entry prev = m.put(code, entry);
        if (prev != null) {
            throw new IllegalStateException("Duplicate error code in catalog: " + code
                    + " (prev=" + prev + ", next=" + entry + ") -- a code cannot be reused");
        }
    }
}
