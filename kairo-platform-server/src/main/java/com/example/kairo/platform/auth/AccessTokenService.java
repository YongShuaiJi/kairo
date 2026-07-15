package com.example.kairo.platform.auth;

import com.example.kairo.platform.persistence.mapper.AccessTokenMapper;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AccessTokenService {

    private static final String SUPER_ADMIN_USER_ID = "user-system";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final AccessTokenMapper accessTokenMapper;
    private final Clock clock;

    @Autowired
    public AccessTokenService(AccessTokenMapper accessTokenMapper) {
        this(accessTokenMapper, Clock.systemUTC());
    }

    AccessTokenService(AccessTokenMapper accessTokenMapper, Clock clock) {
        this.accessTokenMapper = accessTokenMapper;
        this.clock = clock;
    }

    public TokenPrincipal authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw PlatformException.unauthorized("Bearer token is required");
        }
        Map<String, Object> raw = accessTokenMapper.activeTokenByHash(hash(rawToken));
        if (raw == null) {
            throw PlatformException.unauthorized("Bearer token is invalid, expired, or revoked");
        }
        Map<String, Object> token = normalize(raw);
        String subjectType = String.valueOf(token.get("subject_type"));
        String subjectId = String.valueOf(token.get("subject_id"));
        validateSubject(subjectType, subjectId);
        accessTokenMapper.updateLastUsed(token.get("id"), Timestamp.from(clock.instant()));
        return new TokenPrincipal(
                String.valueOf(token.get("id")),
                subjectType,
                subjectId,
                "AGENT".equals(subjectType) ? "agent" : "local-token",
                parseScope(token.get("scope_json")),
                nullableString(token.get("source")),
                parseInt(token.get("max_sessions"))
        );
    }

    /**
     * Parse a persisted {@code scope_json} for authentication (V1.6 fail-closed).
     * <ul>
     *   <li>{@code null}/blank &rarr; {@code null} (no narrowing; inherit the subject's full set).</li>
     *   <li>A JSON array of non-blank strings &rarr; that capability set (possibly empty for {@code []},
     *       which grants zero capabilities).</li>
     *   <li>Any other shape (object/string/number/bool), a malformed JSON document, or an array
     *       containing non-string/blank elements &rarr; {@link PlatformException#unauthorized(String, String)}
     *       so a corrupted row can never widen to the subject's full set.</li>
     * </ul>
     */
    private static java.util.Set<String> parseScope(Object scopeJson) {
        if (scopeJson == null || String.valueOf(scopeJson).isBlank()) {
            return null;
        }
        Object parsed;
        try {
            parsed = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(String.valueOf(scopeJson), Object.class);
        } catch (Exception e) {
            throw PlatformException.unauthorized("TOKEN_SCOPE_INVALID",
                    "Token scope 损坏，拒绝认证");
        }
        if (!(parsed instanceof java.util.List<?> list)) {
            throw PlatformException.unauthorized("TOKEN_SCOPE_INVALID",
                    "Token scope 必须是能力数组，拒绝认证");
        }
        java.util.Set<String> caps = new java.util.LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank()) {
                throw PlatformException.unauthorized("TOKEN_SCOPE_INVALID",
                        "Token scope 包含非字符串或空能力，拒绝认证");
            }
            caps.add(s);
        }
        return caps;
    }

    private static String nullableString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * Parse a persisted {@code max_sessions} for authentication (V1.6 fail-closed).
     * {@code null} &rarr; unlimited; a positive integer &rarr; that limit; any non-positive
     * or non-integer value &rarr; {@link PlatformException#unauthorized(String, String)} so a
     * corrupted row can never silently become unlimited.
     */
    private static Integer parseInt(Object o) {
        if (o == null) {
            return null;
        }
        int value;
        if (o instanceof Number n) {
            value = n.intValue();
        } else {
            try {
                value = Integer.parseInt(String.valueOf(o).trim());
            } catch (NumberFormatException e) {
                throw PlatformException.unauthorized("TOKEN_MAX_SESSIONS_INVALID",
                        "Token maxSessions 损坏，拒绝认证");
            }
        }
        if (value <= 0) {
            throw PlatformException.unauthorized("TOKEN_MAX_SESSIONS_INVALID",
                    "Token maxSessions 必须为正数，拒绝认证");
        }
        return value;
    }

    public Map<String, Object> describe(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw PlatformException.unauthorized("Bearer token is required");
        }
        Map<String, Object> token = accessTokenMapper.describeActiveTokenByHash(hash(rawToken));
        if (token == null) {
            throw PlatformException.unauthorized("Bearer token is invalid, expired, or revoked");
        }
        return normalize(token);
    }

    @Transactional
    public Map<String, Object> issue(RequestContext context, Map<String, Object> request) {
        String subjectType = optional(request, "subjectType", "USER").trim().toUpperCase();
        String subjectId = optional(request, "subjectId", optional(request, "username", "")).trim();
        if (subjectId.isBlank()) {
            throw PlatformException.badRequest("MISSING_FIELD", "username is required");
        }
        if (!"USER".equals(subjectType) && !"AGENT".equals(subjectType)) {
            throw PlatformException.badRequest("INVALID_SUBJECT_TYPE", "subjectType must be USER or AGENT");
        }
        String displayName = optional(request, "displayName", subjectId).trim();
        if (displayName.isBlank()) {
            displayName = subjectId;
        }
        String scopeJson = scopeJsonFromRequest(request);
        String source = sourceFromRequest(request);
        Integer maxSessions = maxSessionsFromRequest(request);
        if ("USER".equals(subjectType)) {
            Map<String, Object> user = ensureLocalUser(subjectId, displayName);
            accessTokenMapper.deleteUserTokens(String.valueOf(user.get("id")));
            return createUserToken(context.actor(), user, validatedExpiresAt(request, clock.instant()),
                    scopeJson, source, maxSessions);
        } else {
            validateSubject(subjectType, subjectId);
        }
        return createToken(context.actor(), subjectType, subjectId, displayName,
                validatedExpiresAt(request, clock.instant()), scopeJson, source, maxSessions);
    }

    /**
     * Serialise the requested capability scope to JSON (V1.6 fail-closed at issue time).
     * <ul>
     *   <li>{@code null} &rarr; {@code null} (no narrowing; inherit the subject's full set).</li>
     *   <li>A list of non-blank strings &rarr; that list as JSON.</li>
     *   <li>An empty list &rarr; {@code "[]"} (explicit zero-capability scope; stored, not
     *       silently widened to full access).</li>
     *   <li>Any other shape (string/object/number) or a list containing non-string/blank
     *       items &rarr; {@code 400 INVALID_TOKEN_SCOPE}.</li>
     * </ul>
     */
    private static String scopeJsonFromRequest(Map<String, Object> request) {
        Object scope = request.get("scope");
        if (scope == null) {
            return null;
        }
        if (!(scope instanceof List<?> list)) {
            throw PlatformException.badRequest("INVALID_TOKEN_SCOPE",
                    "scope 必须是能力字符串数组");
        }
        List<String> caps = new java.util.ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank()) {
                throw PlatformException.badRequest("INVALID_TOKEN_SCOPE",
                        "scope 数组只能包含非空字符串");
            }
            caps.add(s);
        }
        // Explicit empty scope -> "[]" (zero capabilities), distinct from null (inherit all).
        return PlatformJson.write(caps);
    }

    private static String sourceFromRequest(Map<String, Object> request) {
        Object source = request.get("source");
        if (source == null || String.valueOf(source).isBlank()) {
            return null;
        }
        String s = String.valueOf(source).trim().toLowerCase();
        return switch (s) {
            case "web", "cli", "sdk", "mcp", "automation", "local-token", "agent" -> s;
            default -> "custom";
        };
    }

    /**
     * Parse the requested {@code maxSessions} (V1.6 fail-closed at issue time).
     * {@code null} &rarr; unlimited; a positive integer/numeric string &rarr; that value;
     * any non-positive, fractional, or non-numeric value &rarr; {@code 400 INVALID_TOKEN_MAX_SESSIONS}.
     * A JSON {@code 2.5} arrives as a {@code Double} and is rejected (not silently truncated to 2).
     */
    private static Integer maxSessionsFromRequest(Map<String, Object> request) {
        Object value = request.get("maxSessions");
        if (value == null) {
            return null;
        }
        int parsed;
        if (value instanceof Number n) {
            // Reject fractional/BigDecimal numbers; only integral values may coerce to a session limit.
            double d = n.doubleValue();
            if (!Double.isFinite(d) || d != Math.floor(d)) {
                throw PlatformException.badRequest("INVALID_TOKEN_MAX_SESSIONS",
                        "maxSessions 必须为正整数");
            }
            parsed = n.intValue();
        } else {
            String raw = String.valueOf(value).trim();
            if (raw.isEmpty()) {
                throw PlatformException.badRequest("INVALID_TOKEN_MAX_SESSIONS",
                        "maxSessions 必须为正整数");
            }
            try {
                parsed = Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                throw PlatformException.badRequest("INVALID_TOKEN_MAX_SESSIONS",
                        "maxSessions 必须为正整数");
            }
        }
        if (parsed <= 0) {
            throw PlatformException.badRequest("INVALID_TOKEN_MAX_SESSIONS",
                    "maxSessions 必须为正整数");
        }
        return parsed;
    }

    public List<Map<String, Object>> list() {
        return accessTokenMapper.listVisibleTokens().stream().map(this::normalize).toList();
    }

    public List<Map<String, Object>> listUsers() {
        return accessTokenMapper.listUsers().stream().map(row -> {
            Map<String, Object> user = normalize(row);
            user.put("role", Boolean.TRUE.equals(user.get("super_admin")) ? "SUPER_ADMIN" : "BUSINESS_USER");
            return user;
        }).toList();
    }

    @Transactional
    public Map<String, Object> updateSelfProfile(RequestContext context, Map<String, Object> request) {
        Map<String, Object> existing = normalizeUserById(context.actor());
        String userId = String.valueOf(existing.get("id"));
        String oldUsername = String.valueOf(existing.get("username"));
        String newUsername = optional(request, "username", oldUsername).trim();
        if (newUsername.isBlank()) {
            throw PlatformException.badRequest("MISSING_FIELD", "username is required");
        }
        String displayName = optional(request, "displayName", newUsername).trim();
        if (displayName.isBlank()) {
            displayName = newUsername;
        }

        try {
            int updated = accessTokenMapper.updateUserProfile(userId, newUsername, displayName);
            if (updated == 0) {
                throw PlatformException.notFound("user", oldUsername);
            }
        } catch (DuplicateKeyException e) {
            throw PlatformException.conflict("USERNAME_CONFLICT",
                    "用户名已存在，请换一个用户名", Map.of("username", newUsername));
        }
        accessTokenMapper.updateUserTokenDisplayName(userId, displayName);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("subject", newUsername);
        response.put("displayName", displayName);
        response.put("previousSubject", existing.get("username"));
        return response;
    }

    @Transactional
    public Map<String, Object> replaceSelfToken(RequestContext context, String rawToken) {
        Map<String, Object> user = normalizeUserById(context.actor());
        Map<String, Object> described = describe(rawToken);
        Instant expiresAt = instantValue(described.get("expires_at"));
        return replaceUserTokenInternal(user, context.actor(), expiresAt,
                nullableString(described.get("scope_json")),
                nullableString(described.get("source")),
                parseInt(described.get("max_sessions")));
    }

    @Transactional
    public Map<String, Object> replaceUserToken(RequestContext context, String username, Map<String, Object> request) {
        String normalizedUsername = username == null ? "" : username.trim();
        Map<String, Object> user = normalizeUser(normalizedUsername);
        return replaceUserTokenInternal(user, context.actor(), validatedExpiresAt(request, clock.instant()),
                scopeJsonFromRequest(request), sourceFromRequest(request), maxSessionsFromRequest(request));
    }

    @Transactional
    public Map<String, Object> renewUserTokens(RequestContext context, String username, Map<String, Object> request) {
        String normalizedUsername = username == null ? "" : username.trim();
        Map<String, Object> user = normalizeUser(normalizedUsername);
        String userId = String.valueOf(user.get("id"));
        if (userId.equals(context.actor())) {
            throw PlatformException.badRequest("CANNOT_RENEW_SELF_TOKEN", "不能给自己续期，请更换自己的 Token");
        }
        Instant expiresAt = validatedExpiresAt(request, clock.instant());
        int updated = accessTokenMapper.renewActiveUserTokens(userId, timestamp(expiresAt));
        if (updated == 0) {
            throw PlatformException.notFound("platform_access_token", normalizedUsername);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", normalizedUsername);
        response.put("updatedTokenCount", updated);
        response.put("expiresAt", expiresAt);
        return response;
    }

    @Transactional
    public void deleteUser(String username) {
        String normalizedUsername = username == null ? "" : username.trim();
        Map<String, Object> user = normalizeUser(normalizedUsername);
        if (SUPER_ADMIN_USER_ID.equals(String.valueOf(user.get("id")))) {
            throw PlatformException.badRequest("CANNOT_DELETE_SUPER_ADMIN", "不能删除超级管理员");
        }
        accessTokenMapper.deleteUserTokens(String.valueOf(user.get("id")));
        accessTokenMapper.deleteExternalIdentities(String.valueOf(user.get("id")));
        accessTokenMapper.deleteUserRoleBindings(String.valueOf(user.get("id")));
        int deleted = accessTokenMapper.deleteUser(normalizedUsername);
        if (deleted == 0) {
            throw PlatformException.notFound("user", normalizedUsername);
        }
    }

    @Transactional
    public Map<String, Object> renew(RequestContext context, String id, Map<String, Object> request) {
        Map<String, Object> rawExisting = accessTokenMapper.tokenSubject(id);
        if (rawExisting == null) {
            throw PlatformException.notFound("platform_access_token", id);
        }
        Map<String, Object> existing = normalize(rawExisting);

        String subjectType = String.valueOf(existing.get("subject_type"));
        String subjectId = String.valueOf(existing.get("subject_id"));
        if ("USER".equals(subjectType) && subjectId.equals(context.actor())) {
            throw PlatformException.badRequest("CANNOT_RENEW_SELF_TOKEN", "不能给自己续期，请更换自己的 Token");
        }
        validateSubject(subjectType, subjectId);
        Instant expiresAt = validatedExpiresAt(request, clock.instant());
        int updated = accessTokenMapper.renewToken(id, timestamp(expiresAt));
        if (updated == 0) {
            throw PlatformException.notFound("platform_access_token", id);
        }

        return normalize(accessTokenMapper.visibleToken(id));
    }

    public void revoke(String id) {
        int updated = accessTokenMapper.revokeToken(id, Timestamp.from(clock.instant()));
        if (updated == 0) {
            throw PlatformException.notFound("platform_access_token", id);
        }
    }

    public void installBootstrapToken(String rawToken, String actor, long ttlDays) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        Map<String, Object> bootstrapActor = ensureBootstrapUser(actor);
        Instant now = clock.instant();
        accessTokenMapper.deleteDifferentBootstrapToken(hash(rawToken));
        if (accessTokenMapper.countBootstrapToken() > 0) {
            return;
        }
        accessTokenMapper.insertBootstrapToken(hash(rawToken),
                String.valueOf(bootstrapActor.get("id")),
                String.valueOf(bootstrapActor.get("display_name")),
                Timestamp.from(now),
                Timestamp.from(now.plus(ttlDays, ChronoUnit.DAYS)));
    }

    private void validateSubject(String subjectType, String subjectId) {
        int count = "AGENT".equals(subjectType)
                ? accessTokenMapper.countActiveAgent(subjectId)
                : accessTokenMapper.countActiveUser(subjectId);
        if (count == 0) {
            throw PlatformException.notFound(subjectType.toLowerCase(), subjectId);
        }
    }

    private Map<String, Object> ensureLocalUser(String username, String displayName) {
        if (accessTokenMapper.activateUserForToken(username, displayName) > 0) {
            return normalizeUser(username);
        }
        try {
            accessTokenMapper.insertUserForToken("user-" + UUID.randomUUID(), username, displayName);
        } catch (DuplicateKeyException ignored) {
            accessTokenMapper.activateUserForToken(username, displayName);
        }
        return normalizeUser(username);
    }

    private Map<String, Object> ensureBootstrapUser(String username) {
        String normalized = username == null || username.isBlank() ? "system" : username.trim();
        Map<String, Object> existingBootstrap = accessTokenMapper.userById(SUPER_ADMIN_USER_ID);
        if (existingBootstrap != null) {
            Map<String, Object> normalizedBootstrap = normalize(existingBootstrap);
            if ("ACTIVE".equals(String.valueOf(normalizedBootstrap.get("status")))) {
                return normalizedBootstrap;
            }
        }
        if (accessTokenMapper.activateBootstrapUser(SUPER_ADMIN_USER_ID, normalized, normalized) > 0) {
            return normalizeUserById(SUPER_ADMIN_USER_ID);
        }
        try {
            accessTokenMapper.insertUserForToken(SUPER_ADMIN_USER_ID, normalized, normalized);
        } catch (DuplicateKeyException ignored) {
            accessTokenMapper.activateBootstrapUser(SUPER_ADMIN_USER_ID, normalized, normalized);
        }
        return normalizeUserById(SUPER_ADMIN_USER_ID);
    }

    private Map<String, Object> normalizeUser(String username) {
        if (username == null || username.isBlank()) {
            throw PlatformException.badRequest("MISSING_FIELD", "username is required");
        }
        Map<String, Object> user = accessTokenMapper.userByUsername(username.trim());
        if (user == null) {
            throw PlatformException.notFound("user", username.trim());
        }
        Map<String, Object> normalized = normalize(user);
        if (!"ACTIVE".equals(String.valueOf(normalized.get("status")))) {
            throw PlatformException.notFound("user", username.trim());
        }
        return normalized;
    }

    private Map<String, Object> normalizeUserById(String userId) {
        if (userId == null || userId.isBlank()) {
            throw PlatformException.badRequest("MISSING_FIELD", "userId is required");
        }
        Map<String, Object> user = accessTokenMapper.userById(userId.trim());
        if (user == null) {
            throw PlatformException.notFound("user", userId.trim());
        }
        Map<String, Object> normalized = normalize(user);
        if (!"ACTIVE".equals(String.valueOf(normalized.get("status")))) {
            throw PlatformException.notFound("user", userId.trim());
        }
        return normalized;
    }

    private Map<String, Object> replaceUserTokenInternal(Map<String, Object> user, String createdBy, Instant expiresAt,
                                                         String scopeJson, String source, Integer maxSessions) {
        accessTokenMapper.deleteUserTokens(String.valueOf(user.get("id")));
        return createUserToken(createdBy, user, expiresAt, scopeJson, source, maxSessions);
    }

    private Map<String, Object> createUserToken(String createdBy, Map<String, Object> user, Instant expiresAt,
                                                String scopeJson, String source, Integer maxSessions) {
        Map<String, Object> response = createToken(createdBy, "USER",
                String.valueOf(user.get("id")),
                String.valueOf(user.get("display_name")),
                expiresAt, scopeJson, source, maxSessions);
        response.put("subjectId", user.get("username"));
        response.put("userId", user.get("id"));
        return response;
    }

    private Map<String, Object> createToken(String createdBy, String subjectType, String subjectId,
                                            String displayName, Instant expiresAt,
                                            String scopeJson, String source, Integer maxSessions) {
        Instant now = clock.instant();
        String rawToken = generateToken();
        String id = "token-" + UUID.randomUUID();
        accessTokenMapper.insertToken(id, hash(rawToken), subjectType, subjectId, displayName,
                createdBy, Timestamp.from(now), timestamp(expiresAt), scopeJson, source, maxSessions);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("token", rawToken);
        response.put("subjectType", subjectType);
        response.put("subjectId", subjectId);
        response.put("displayName", displayName);
        response.put("status", "VALID");
        response.put("expiresAt", expiresAt);
        if (source != null) {
            response.put("source", source);
        }
        if (scopeJson != null) {
            response.put("scope", PlatformJson.readList(scopeJson));
        }
        if (maxSessions != null) {
            response.put("maxSessions", maxSessions);
        }
        return response;
    }

    private String optional(Map<String, Object> request, String name, String fallback) {
        Object value = request.get(name);
        return value == null ? fallback : String.valueOf(value);
    }

    private Instant expiresAt(Map<String, Object> request, Instant now) {
        Object value = request.get("expiresAt");
        if (value == null || String.valueOf(value).isBlank()) {
            if (!request.containsKey("ttlSeconds") || String.valueOf(request.get("ttlSeconds")).isBlank()) {
                return null;
            }
            return now.plusSeconds(longValue(request, "ttlSeconds", 86_400L));
        }
        String raw = String.valueOf(value);
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                try {
                    return LocalDateTime.parse(raw).atZone(clock.getZone()).toInstant();
                } catch (DateTimeParseException e) {
                    throw PlatformException.badRequest("INVALID_TOKEN_EXPIRES_AT",
                            "Token 过期时间格式不正确");
                }
            }
        }
    }

    private Instant validatedExpiresAt(Map<String, Object> request, Instant now) {
        Instant expiresAt = expiresAt(request, now);
        if (expiresAt == null) {
            return null;
        }
        long ttlSeconds = ChronoUnit.SECONDS.between(now, expiresAt);
        if (ttlSeconds < 60 || ttlSeconds > 31_536_000L) {
            throw PlatformException.badRequest("INVALID_TOKEN_TTL",
                    "Token 过期时间必须在当前时间 60 秒到 365 天之间");
        }
        return expiresAt;
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instantValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof java.util.Date date) {
            return date.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    private long longValue(Map<String, Object> request, String name, long fallback) {
        Object value = request.get(name);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private Map<String, Object> normalize(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key.toLowerCase(), value));
        return result;
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record TokenPrincipal(String tokenId, String subjectType, String subjectId,
                                  String identitySource, java.util.Set<String> scope,
                                  String source, Integer maxSessions) {
        public TokenPrincipal(String tokenId, String subjectType, String subjectId, String identitySource) {
            this(tokenId, subjectType, subjectId, identitySource, null, null, null);
        }
    }
}
