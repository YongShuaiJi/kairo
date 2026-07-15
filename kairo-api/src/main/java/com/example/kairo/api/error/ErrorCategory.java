package com.example.kairo.api.error;

/**
 * Stable, machine-readable classification of an API error (V1.6 &sect;2.4).
 *
 * <p>AI clients must branch on {@link #code} or {@link #category}, never on the
 * human-readable {@code message}. Categories are fixed for the V1 API surface.
 */
public enum ErrorCategory {
    /** Request was malformed, semantically invalid or failed validation. */
    VALIDATION,
    /** The caller was not authenticated. */
    AUTHENTICATION,
    /** The caller was authenticated but lacks a required capability or scope. */
    AUTHORIZATION,
    /** The referenced resource does not exist. */
    NOT_FOUND,
    /** A precondition (If-Match / expected version / state machine) was not met. */
    CONFLICT,
    /** A capability the client requested is not supported by the target agent/runtime. */
    CAPABILITY,
    /** An explicitly modelled business rule rejected the operation. */
    BUSINESS_RULE,
    /** A rate limit or quota was exceeded. Retriable after a delay. */
    RATE_LIMITED,
    /** A long-running operation is still in progress; poll the Operation resource. */
    OPERATION_IN_PROGRESS,
    /** An internal or downstream failure. {@code retryable} indicates whether to retry. */
    INTERNAL
}
