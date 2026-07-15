package com.example.kairo.platform.service;

import java.util.Objects;
import java.util.Set;

/**
 * Scope bound to an API token (V1.6 &sect;5.1 "对 API Token 增加 scope、调用来源和可选会话上限").
 *
 * <p>A token scope can only <em>narrow</em> the subject's existing capabilities;
 * it never grants capabilities the subject does not have. When
 * {@link #capabilities()} is non-null and non-empty, the token is restricted to
 * exactly that set. {@link #maxSessions()} bounds concurrent automation sessions.
 *
 * @param tokenId      the platform_access_token id
 * @param source       origin: {@code web}, {@code cli}, {@code sdk}, {@code mcp}, {@code local-token}, {@code agent}
 * @param capabilities narrowest allowed capability set; null/empty = inherit subject's full set
 * @param maxSessions  max concurrent automation sessions; null = unlimited
 */
public record TokenScope(String tokenId, String source, Set<String> capabilities, Integer maxSessions) {

    public TokenScope {
        tokenId = tokenId == null || tokenId.isBlank() ? null : tokenId;
        source = source == null || source.isBlank() ? null : source;
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        maxSessions = maxSessions == null || maxSessions <= 0 ? null : maxSessions;
    }

    /** A scope with no narrowing (full subject capabilities, unlimited sessions). */
    public static TokenScope unrestricted(String tokenId, String source) {
        return new TokenScope(tokenId, source, null, null);
    }

    /** Whether this scope narrows capabilities (vs. inheriting the subject's full set). */
    public boolean isNarrowed() {
        return !capabilities.isEmpty();
    }

    /** True if the token is permitted the given capability under this scope. */
    public boolean permits(String capability) {
        Objects.requireNonNull(capability, "capability");
        return capabilities.isEmpty() || capabilities.contains(capability);
    }
}
