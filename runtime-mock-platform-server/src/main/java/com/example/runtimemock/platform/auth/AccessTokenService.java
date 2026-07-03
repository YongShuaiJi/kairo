package com.example.runtimemock.platform.auth;

import com.example.runtimemock.platform.service.PlatformException;
import com.example.runtimemock.platform.service.RequestContext;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TOKEN_VISIBLE_COLUMNS = """
            id, subject_type, subject_id,
            case
                when status = 'ACTIVE' and (expires_at is null or expires_at > current_timestamp) then 'VALID'
                else 'INVALID'
            end as status,
            created_by, created_at, expires_at, last_used_at, revoked_at
            """;

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public AccessTokenService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    AccessTokenService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public TokenPrincipal authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw PlatformException.unauthorized("Bearer token is required");
        }
        try {
            Map<String, Object> token = normalize(jdbcTemplate.queryForMap("""
                    select *
                      from platform_access_token
                     where token_hash = ?
                       and status = 'ACTIVE'
                       and (expires_at is null or expires_at > current_timestamp)
                    """, hash(rawToken)));
            String subjectType = String.valueOf(token.get("subject_type"));
            String subjectId = String.valueOf(token.get("subject_id"));
            validateSubject(subjectType, subjectId);
            jdbcTemplate.update("""
                    update platform_access_token
                       set last_used_at = ?
                     where id = ?
                    """, Timestamp.from(clock.instant()), token.get("id"));
            return new TokenPrincipal(
                    String.valueOf(token.get("id")),
                    subjectType,
                    subjectId,
                    "AGENT".equals(subjectType) ? "agent" : "local-token"
            );
        } catch (EmptyResultDataAccessException e) {
            throw PlatformException.unauthorized("Bearer token is invalid, expired, or revoked");
        }
    }

    public Map<String, Object> describe(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw PlatformException.unauthorized("Bearer token is required");
        }
        try {
            return normalize(jdbcTemplate.queryForMap("""
                    select id, subject_type, subject_id,
                           'VALID' as status,
                           created_at, expires_at, last_used_at
                      from platform_access_token
                     where token_hash = ?
                       and status = 'ACTIVE'
                       and (expires_at is null or expires_at > current_timestamp)
                    """, hash(rawToken)));
        } catch (EmptyResultDataAccessException e) {
            throw PlatformException.unauthorized("Bearer token is invalid, expired, or revoked");
        }
    }

    @Transactional
    public Map<String, Object> issue(RequestContext context, Map<String, Object> request) {
        String subjectType = optional(request, "subjectType", "USER").toUpperCase();
        String subjectId = optional(request, "subjectId", optional(request, "username", ""));
        if (subjectId.isBlank()) {
            throw PlatformException.badRequest("MISSING_FIELD", "username is required");
        }
        if (!"USER".equals(subjectType) && !"AGENT".equals(subjectType)) {
            throw PlatformException.badRequest("INVALID_SUBJECT_TYPE", "subjectType must be USER or AGENT");
        }
        validateSubject(subjectType, subjectId);
        Instant now = clock.instant();
        Instant expiresAt = validatedExpiresAt(request, now);
        String rawToken = generateToken();
        String id = "token-" + UUID.randomUUID();
        String displayName = optional(request, "displayName", subjectId);
        jdbcTemplate.update("""
                insert into platform_access_token(
                    id, token_hash, subject_type, subject_id, display_name, status,
                    created_by, created_at, expires_at, last_used_at, revoked_at
                ) values (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, null, null)
                """, id, hash(rawToken), subjectType, subjectId, displayName,
                context.actor(), Timestamp.from(now), timestamp(expiresAt));
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

    public List<Map<String, Object>> list() {
        return jdbcTemplate.queryForList("""
                select %s
                  from platform_access_token
                 order by created_at desc, id
                """.formatted(TOKEN_VISIBLE_COLUMNS)).stream().map(this::normalize).toList();
    }

    @Transactional
    public Map<String, Object> renew(RequestContext context, String id, Map<String, Object> request) {
        Map<String, Object> existing;
        try {
            existing = normalize(jdbcTemplate.queryForMap("""
                    select id, subject_type, subject_id
                      from platform_access_token
                     where id = ?
                    """, id));
        } catch (EmptyResultDataAccessException e) {
            throw PlatformException.notFound("platform_access_token", id);
        }

        String subjectType = String.valueOf(existing.get("subject_type"));
        String subjectId = String.valueOf(existing.get("subject_id"));
        validateSubject(subjectType, subjectId);
        Instant expiresAt = validatedExpiresAt(request, clock.instant());
        int updated = jdbcTemplate.update("""
                update platform_access_token
                   set status = 'ACTIVE',
                       expires_at = ?,
                       revoked_at = null
                 where id = ?
                """, timestamp(expiresAt), id);
        if (updated == 0) {
            throw PlatformException.notFound("platform_access_token", id);
        }

        return normalize(jdbcTemplate.queryForMap("""
                select %s
                  from platform_access_token
                 where id = ?
                """.formatted(TOKEN_VISIBLE_COLUMNS), id));
    }

    public void revoke(String id) {
        int updated = jdbcTemplate.update("""
                update platform_access_token
                   set status = 'REVOKED', revoked_at = ?
                 where id = ? and status = 'ACTIVE'
                """, Timestamp.from(clock.instant()), id);
        if (updated == 0) {
            throw PlatformException.notFound("platform_access_token", id);
        }
    }

    public void installBootstrapToken(String rawToken, String actor, long ttlDays) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        validateSubject("USER", actor);
        Instant now = clock.instant();
        jdbcTemplate.update("""
                delete from platform_access_token
                 where id = 'token-bootstrap' and token_hash <> ?
                """, hash(rawToken));
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from platform_access_token where id = 'token-bootstrap'",
                Integer.class);
        if (count != null && count > 0) {
            return;
        }
        jdbcTemplate.update("""
                insert into platform_access_token(
                    id, token_hash, subject_type, subject_id, display_name, status,
                    created_by, created_at, expires_at, last_used_at, revoked_at
                ) values ('token-bootstrap', ?, 'USER', ?, ?,
                          'ACTIVE', 'system', ?, ?, null, null)
                """, hash(rawToken), actor, actor, Timestamp.from(now),
                Timestamp.from(now.plus(ttlDays, ChronoUnit.DAYS)));
    }

    private void validateSubject(String subjectType, String subjectId) {
        String sql = "AGENT".equals(subjectType)
                ? "select count(*) from agent_instance where id = ? and status <> 'REMOVED'"
                : "select count(*) from user_account where username = ? and status = 'ACTIVE'";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class, subjectId);
        if (count == null || count == 0) {
            throw PlatformException.notFound(subjectType.toLowerCase(), subjectId);
        }
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
