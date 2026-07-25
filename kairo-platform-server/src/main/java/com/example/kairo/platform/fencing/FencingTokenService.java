package com.example.kairo.platform.fencing;

import com.example.kairo.platform.persistence.mapper.BusinessIdMapper;
import com.example.kairo.platform.persistence.mapper.FencingTokenMapper;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.RequestContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
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

    private final FencingTokenMapper fencingTokenMapper;
    private final BusinessIdMapper businessIdMapper;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final FencingProperties properties;
    private final Clock clock;

    @Autowired
    public FencingTokenService(FencingTokenMapper fencingTokenMapper, BusinessIdMapper businessIdMapper,
                               ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                               FencingProperties properties) {
        this(fencingTokenMapper, businessIdMapper, redisTemplateProvider, properties, Clock.systemUTC());
    }

    FencingTokenService(FencingTokenMapper fencingTokenMapper, BusinessIdMapper businessIdMapper,
                        ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                        FencingProperties properties, Clock clock) {
        this.fencingTokenMapper = fencingTokenMapper;
        this.businessIdMapper = businessIdMapper;
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
        fencingTokenMapper.insertToken("fencing-" + UUID.randomUUID(), resourceType, resourceId, purpose, token, sequence,
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
        int updated = fencingTokenMapper.consumeIssuedToken(resourceType, resourceId, token,
                context.actor(), Timestamp.from(now));
        if (updated == 0) {
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
            Long value;
            try {
                value = redisTemplate.opsForValue().increment(properties.getKeyPrefix() + resourceKey);
            } catch (org.springframework.dao.DataAccessException e) {
                // V1.7 M1-F §8.6 item 2: Redis enabled but unreachable. The fencing write fails closed
                // as a structured error; the surrounding @Transactional issue() rolls back, so the
                // authoritative PostgreSQL state is never left half-written.
                throw PlatformException.conflict("REDIS_UNAVAILABLE",
                        "Redis fencing is enabled but Redis is unreachable: " + rootMessage(e),
                        Map.of("resourceKey", resourceKey));
            }
            if (value == null) {
                throw PlatformException.conflict("REDIS_FENCING_FAILED",
                        "Redis did not return a fencing sequence", Map.of("resourceKey", resourceKey));
            }
            upsertDbSequence(resourceKey, value);
            return value;
        }
        int updated = businessIdMapper.incrementSequence(resourceKey, Timestamp.from(clock.instant()));
        if (updated == 0) {
            try {
                businessIdMapper.insertSequence(resourceKey, 1L, Timestamp.from(clock.instant()));
                return 1L;
            } catch (DuplicateKeyException ignored) {
                businessIdMapper.incrementSequence(resourceKey, Timestamp.from(clock.instant()));
            }
        }
        Long value = businessIdMapper.currentSequence(resourceKey);
        if (value == null) {
            throw PlatformException.conflict("FENCING_SEQUENCE_FAILED",
                    "Database did not return a fencing sequence", Map.of("resourceKey", resourceKey));
        }
        return value;
    }

    private void upsertDbSequence(String resourceKey, long value) {
        int updated = businessIdMapper.updateSequenceValue(resourceKey, value, Timestamp.from(clock.instant()));
        if (updated == 0) {
            businessIdMapper.insertSequence(resourceKey, value, Timestamp.from(clock.instant()));
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }
}
