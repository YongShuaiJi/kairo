package com.example.runtimemock.platform.fencing;

import com.example.runtimemock.platform.service.PlatformException;
import com.example.runtimemock.platform.service.RequestContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class FencingTokenService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final FencingProperties properties;
    private final Clock clock;

    @Autowired
    public FencingTokenService(JdbcTemplate jdbcTemplate, ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                               FencingProperties properties) {
        this(jdbcTemplate, redisTemplateProvider, properties, Clock.systemUTC());
    }

    FencingTokenService(JdbcTemplate jdbcTemplate, ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                        FencingProperties properties, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplateProvider = redisTemplateProvider;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> issue(RequestContext context, String resourceType, String resourceId,
                                     String purpose, long ttlSeconds) {
        Instant now = clock.instant();
        long effectiveTtl = ttlSeconds > 0 ? ttlSeconds : properties.getDefaultTtlSeconds();
        long sequence = nextSequence(resourceType, resourceId);
        String token = resourceType + ":" + resourceId + ":" + sequence + ":" + UUID.randomUUID();
        Instant expiresAt = now.plusSeconds(effectiveTtl);
        jdbcTemplate.update("""
                insert into fencing_token(
                    id, resource_type, resource_id, purpose, token, sequence, owner, status,
                    lease_expires_at, created_at, correlation_id
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "fencing-" + UUID.randomUUID(), resourceType, resourceId, purpose, token, sequence,
                context.actor(), "ISSUED", Timestamp.from(expiresAt), Timestamp.from(now), context.correlationId());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("resourceType", resourceType);
        response.put("resourceId", resourceId);
        response.put("purpose", purpose);
        response.put("token", token);
        response.put("sequence", sequence);
        response.put("leaseExpiresAt", expiresAt.toString());
        response.put("redisBacked", properties.isRedisEnabled());
        return response;
    }

    @Transactional
    public void consume(RequestContext context, String resourceType, String resourceId, String token) {
        Instant now = clock.instant();
        Integer updated = jdbcTemplate.update("""
                update fencing_token
                   set status = 'CONSUMED', consumed_at = ?
                 where resource_type = ?
                   and resource_id = ?
                   and token = ?
                   and owner = ?
                   and status = 'ISSUED'
                   and lease_expires_at > ?
                """, Timestamp.from(now), resourceType, resourceId, token, context.actor(), Timestamp.from(now));
        if (updated == null || updated == 0) {
            throw PlatformException.conflict("FENCING_TOKEN_INVALID",
                    "Fencing token is missing, expired, already consumed, or belongs to another resource",
                    Map.of("resourceType", resourceType, "resourceId", resourceId, "actor", context.actor()));
        }
    }

    private long nextSequence(String resourceType, String resourceId) {
        String resourceKey = resourceType + ":" + resourceId;
        if (properties.isRedisEnabled()) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate == null) {
                throw PlatformException.conflict("REDIS_UNAVAILABLE",
                        "Redis fencing is enabled but no Redis client is available", Map.of("resourceKey", resourceKey));
            }
            Long value = redisTemplate.opsForValue().increment(properties.getKeyPrefix() + resourceKey);
            if (value == null) {
                throw PlatformException.conflict("REDIS_FENCING_FAILED",
                        "Redis did not return a fencing sequence", Map.of("resourceKey", resourceKey));
            }
            upsertDbSequence(resourceKey, value);
            return value;
        }
        int updated = jdbcTemplate.update("""
                update fencing_sequence
                   set current_value = current_value + 1, updated_at = ?
                 where resource_key = ?
                """, Timestamp.from(clock.instant()), resourceKey);
        if (updated == 0) {
            try {
                jdbcTemplate.update("""
                        insert into fencing_sequence(resource_key, current_value, updated_at)
                        values (?, 1, ?)
                        """, resourceKey, Timestamp.from(clock.instant()));
                return 1L;
            } catch (DuplicateKeyException ignored) {
                jdbcTemplate.update("""
                        update fencing_sequence
                           set current_value = current_value + 1, updated_at = ?
                         where resource_key = ?
                        """, Timestamp.from(clock.instant()), resourceKey);
            }
        }
        Long value = jdbcTemplate.queryForObject("""
                select current_value from fencing_sequence where resource_key = ?
                """, Long.class, resourceKey);
        if (value == null) {
            throw PlatformException.conflict("FENCING_SEQUENCE_FAILED",
                    "Database did not return a fencing sequence", Map.of("resourceKey", resourceKey));
        }
        return value;
    }

    private void upsertDbSequence(String resourceKey, long value) {
        int updated = jdbcTemplate.update("""
                update fencing_sequence
                   set current_value = ?, updated_at = ?
                 where resource_key = ?
                """, value, Timestamp.from(clock.instant()), resourceKey);
        if (updated == 0) {
            jdbcTemplate.update("""
                    insert into fencing_sequence(resource_key, current_value, updated_at)
                    values (?, ?, ?)
                    """, resourceKey, value, Timestamp.from(clock.instant()));
        }
    }
}
