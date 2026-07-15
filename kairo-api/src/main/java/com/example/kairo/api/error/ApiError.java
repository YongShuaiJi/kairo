package com.example.kairo.api.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The frozen V1 unified error response (V1.6 &sect;2.4).
 *
 * <p>Every write and read error is serialised to this shape. AI clients MUST
 * branch on {@link #code} or {@link #category}; the {@link #message} is
 * human-readable, localised and may change without notice.
 *
 * <ul>
 *   <li>{@code code} &mdash; stable, e.g. {@code RESOURCE_VERSION_CONFLICT}</li>
 *   <li>{@code category} &mdash; stable classification from {@link ErrorCategory}</li>
 *   <li>{@code retryable} &mdash; whether the caller may retry the identical request</li>
 *   <li>{@code field}/{@code path}/{@code location} &mdash; where a validation error occurred</li>
 *   <li>{@code details} &mdash; structured, machine-readable supplementary data</li>
 *   <li>{@code suggestedActions} &mdash; recovery hints, each marked {@code safe}</li>
 *   <li>{@code correlationId} &mdash; links to audit and server logs</li>
 * </ul>
 *
 * <p>Null optional fields are omitted from the JSON response by the platform's
 * global {@code NON_NULL} Jackson configuration.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        ErrorCategory category,
        boolean retryable,
        String field,
        String path,
        String location,
        Map<String, Object> details,
        List<SuggestedAction> suggestedActions,
        String correlationId
) {

    public ApiError {
        Objects.requireNonNull(code, "code");
        if (code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }
        message = message == null ? "" : message;
        Objects.requireNonNull(category, "category");
        field = field == null || field.isBlank() ? null : field;
        path = path == null || path.isBlank() ? null : path;
        location = location == null || location.isBlank() ? null : location;
        details = details == null ? Map.of() : Map.copyOf(details);
        suggestedActions = suggestedActions == null ? List.of() : List.copyOf(suggestedActions);
        correlationId = correlationId == null ? "" : correlationId;
    }

    /** Builder-style factory for the common case of a simple coded error. */
    public static ApiError of(String code, String message, ErrorCategory category, boolean retryable) {
        return new ApiError(code, message, category, retryable, null, null, null, Map.of(), List.of(), "");
    }

    public ApiError withTarget(ErrorTarget target) {
        if (target == null) {
            return this;
        }
        return new ApiError(code, message, category, retryable,
                target.field(), target.path(), target.location(),
                details, suggestedActions, correlationId);
    }

    public ApiError withCorrelationId(String correlationId) {
        return new ApiError(code, message, category, retryable,
                field, path, location, details, suggestedActions,
                correlationId == null ? "" : correlationId);
    }

    public ApiError withSuggestedActions(List<SuggestedAction> actions) {
        return new ApiError(code, message, category, retryable,
                field, path, location, details,
                actions == null ? List.of() : actions, correlationId);
    }

    public ApiError withDetails(Map<String, Object> details) {
        return new ApiError(code, message, category, retryable,
                field, path, location,
                details == null ? Map.of() : details, suggestedActions, correlationId);
    }
}
