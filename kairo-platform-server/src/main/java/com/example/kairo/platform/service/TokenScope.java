package com.example.kairo.platform.service;

import java.util.Objects;
import java.util.Set;

/**
 * Scope bound to an API token (V1.6 &sect;5.1 "对 API Token 增加 scope、调用来源和可选会话上限").
 *
 * <p>A token scope can only <em>narrow</em> the subject's existing capabilities;
 * it never grants capabilities the subject does not have. Capabilities use a
 * three-state, fail-closed model:
 * <ul>
 *   <li>{@code null} &mdash; no narrowing requested; the token inherits the
 *       subject's full capability set (backwards compatible with unscoped tokens).</li>
 *   <li><em>empty set</em> &mdash; an explicit empty scope ({@code []}); the token
 *       grants <strong>zero</strong> capabilities. It is narrowed, and
 *       {@link #permits(String)} returns false for every capability.</li>
 *   <li><em>non-empty set</em> &mdash; the token is restricted to exactly that set.</li>
 * </ul>
 * {@link #maxSessions()} bounds concurrent automation sessions; it is null
 * (unlimited) or a positive integer &mdash; non-positive values are rejected at
 * issue/authentication time and never reach this record.
 *
 * @param tokenId      the platform_access_token id
 * @param source       origin: {@code web}, {@code cli}, {@code sdk}, {@code mcp}, {@code local-token}, {@code agent}
 * @param capabilities null = inherit subject's full set; empty = zero capabilities; non-empty = narrowed
 * @param maxSessions  max concurrent automation sessions; null = unlimited (must be positive otherwise)
 */
public record TokenScope(String tokenId, String source, Set<String> capabilities, Integer maxSessions) {

    public TokenScope {
        tokenId = tokenId == null || tokenId.isBlank() ? null : tokenId;
        source = source == null || source.isBlank() ? null : source;
        // Preserve null vs empty: null = inherit all, empty = zero capabilities.
        capabilities = capabilities == null ? null : Set.copyOf(capabilities);
        if (maxSessions != null && maxSessions <= 0) {
            // Defensive fail-closed guard; issue/authenticate validate upstream so this
            // should never fire, but a non-positive value must never silently mean unlimited.
            throw new IllegalArgumentException("maxSessions must be positive or null");
        }
    }

    /** A scope with no narrowing (full subject capabilities, unlimited sessions). */
    public static TokenScope unrestricted(String tokenId, String source) {
        return new TokenScope(tokenId, source, null, null);
    }

    /**
     * Whether this scope narrows capabilities (vs. inheriting the subject's full set).
     * An explicit empty scope is narrowed (to zero capabilities).
     */
    public boolean isNarrowed() {
        return capabilities != null;
    }

    /**
     * True if the token is permitted the given capability under this scope.
     * Null (inherit) permits all; empty (zero capabilities) permits none; otherwise
     * the capability must be listed.
     */
    public boolean permits(String capability) {
        Objects.requireNonNull(capability, "capability");
        return capabilities == null || capabilities.contains(capability);
    }
}
