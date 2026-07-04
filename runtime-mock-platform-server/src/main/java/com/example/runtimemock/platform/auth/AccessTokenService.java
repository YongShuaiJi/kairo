package com.example.runtimemock.platform.auth;

import com.example.runtimemock.platform.persistence.mapper.AccessTokenMapper;
import com.example.runtimemock.platform.service.PlatformException;
import com.example.runtimemock.platform.service.RequestContext;
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
                "AGENT".equals(subjectType) ? "agent" : "local-token"
        );
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
        if ("USER".equals(subjectType)) {
            ensureLocalUser(subjectId, displayName);
        } else {
            validateSubject(subjectType, subjectId);
        }
        return createToken(context.actor(), subjectType, subjectId, displayName,
                validatedExpiresAt(request, clock.instant()));
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
        String oldUsername = context.actor();
        Map<String, Object> existing = normalizeUser(oldUsername);
        String newUsername = optional(request, "username", oldUsername).trim();
        if (newUsername.isBlank()) {
            throw PlatformException.badRequest("MISSING_FIELD", "username is required");
        }
        String displayName = optional(request, "displayName", newUsername).trim();
        if (displayName.isBlank()) {
            displayName = newUsername;
        }

        try {
            int updated = accessTokenMapper.updateUserProfile(oldUsername, newUsername, displayName);
            if (updated == 0) {
                throw PlatformException.notFound("user", oldUsername);
            }
        } catch (DuplicateKeyException e) {
            throw PlatformException.conflict("USERNAME_CONFLICT",
                    "用户名已存在，请换一个用户名", Map.of("username", newUsername));
        }
        accessTokenMapper.updateUserTokenSubject(oldUsername, newUsername, displayName);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("subject", newUsername);
        response.put("displayName", displayName);
        response.put("previousSubject", existing.get("username"));
        return response;
    }

    @Transactional
    public Map<String, Object> replaceSelfToken(RequestContext context, String rawToken) {
        Map<String, Object> user = normalizeUser(context.actor());
        Instant expiresAt = instantValue(describe(rawToken).get("expires_at"));
        return replaceUserTokenInternal(
                context.actor(),
                String.valueOf(user.get("display_name")),
                context.actor(),
                expiresAt
        );
    }

    @Transactional
    public Map<String, Object> replaceUserToken(RequestContext context, String username, Map<String, Object> request) {
        String normalizedUsername = username == null ? "" : username.trim();
        Map<String, Object> user = normalizeUser(normalizedUsername);
        return replaceUserTokenInternal(
                normalizedUsername,
                String.valueOf(user.get("display_name")),
                context.actor(),
                validatedExpiresAt(request, clock.instant())
        );
    }

    @Transactional
    public Map<String, Object> renewUserTokens(String username, Map<String, Object> request) {
        String normalizedUsername = username == null ? "" : username.trim();
        normalizeUser(normalizedUsername);
        Instant expiresAt = validatedExpiresAt(request, clock.instant());
        int updated = accessTokenMapper.renewActiveUserTokens(normalizedUsername, timestamp(expiresAt));
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
        accessTokenMapper.deleteUserTokens(normalizedUsername);
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
        ensureBootstrapUser(actor);
        Instant now = clock.instant();
        accessTokenMapper.deleteDifferentBootstrapToken(hash(rawToken));
        if (accessTokenMapper.countBootstrapToken() > 0) {
            return;
        }
        accessTokenMapper.insertBootstrapToken(hash(rawToken), actor, Timestamp.from(now),
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

    private void ensureLocalUser(String username, String displayName) {
        if (accessTokenMapper.activateUserForToken(username, displayName) > 0) {
            return;
        }
        try {
            accessTokenMapper.insertUserForToken("user-" + UUID.randomUUID(), username, displayName);
        } catch (DuplicateKeyException ignored) {
            accessTokenMapper.activateUserForToken(username, displayName);
        }
    }

    private void ensureBootstrapUser(String username) {
        String normalized = username == null || username.isBlank() ? "system" : username.trim();
        if (accessTokenMapper.countActiveUser(normalized) > 0) {
            return;
        }
        if (accessTokenMapper.activateBootstrapUser(SUPER_ADMIN_USER_ID, normalized, normalized) > 0) {
            return;
        }
        try {
            accessTokenMapper.insertUserForToken(SUPER_ADMIN_USER_ID, normalized, normalized);
        } catch (DuplicateKeyException ignored) {
            accessTokenMapper.activateBootstrapUser(SUPER_ADMIN_USER_ID, normalized, normalized);
        }
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

    private Map<String, Object> replaceUserTokenInternal(String username, String displayName,
                                                        String createdBy, Instant expiresAt) {
        accessTokenMapper.revokeActiveUserTokens(username, Timestamp.from(clock.instant()));
        return createToken(createdBy, "USER", username, displayName, expiresAt);
    }

    private Map<String, Object> createToken(String createdBy, String subjectType, String subjectId,
                                            String displayName, Instant expiresAt) {
        Instant now = clock.instant();
        String rawToken = generateToken();
        String id = "token-" + UUID.randomUUID();
        accessTokenMapper.insertToken(id, hash(rawToken), subjectType, subjectId, displayName,
                createdBy, Timestamp.from(now), timestamp(expiresAt));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("token", rawToken);
        response.put("subjectType", subjectType);
        response.put("subjectId", subjectId);
        response.put("displayName", displayName);
        response.put("status", "VALID");
        response.put("expiresAt", expiresAt);
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

    static String hash(String token) {
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

    public record TokenPrincipal(String tokenId, String subjectType, String subjectId, String identitySource) {
    }
}
