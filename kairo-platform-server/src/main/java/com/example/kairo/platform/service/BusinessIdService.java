package com.example.kairo.platform.service;

import com.example.kairo.platform.fencing.FencingProperties;
import com.example.kairo.platform.persistence.mapper.BusinessIdMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

@Service
public class BusinessIdService {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final BusinessIdMapper businessIdMapper;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final FencingProperties fencingProperties;
    private final Clock clock;

    @Autowired
    public BusinessIdService(BusinessIdMapper businessIdMapper,
                             ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                             FencingProperties fencingProperties) {
        this(businessIdMapper, redisTemplateProvider, fencingProperties, Clock.systemDefaultZone());
    }

    BusinessIdService(BusinessIdMapper businessIdMapper,
                      ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                      FencingProperties fencingProperties,
                      Clock clock) {
        this.businessIdMapper = businessIdMapper;
        this.redisTemplateProvider = redisTemplateProvider;
        this.fencingProperties = fencingProperties;
        this.clock = clock;
    }

    public String nextId(String resourceKind, String businessName) {
        String abbreviation = abbreviation(businessName);
        String date = LocalDate.now(clock).format(DATE_FORMAT);
        long sequence = nextSequence(resourceKind, abbreviation, date);
        return "%s-%s-%03d".formatted(abbreviation, date, sequence);
    }

    public String abbreviation(String businessName) {
        String text = businessName == null ? "" : businessName.trim();
        if (text.isBlank()) {
            return "RL";
        }
        String splitCamelCase = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        String[] parts = splitCamelCase.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+");
        StringBuilder initials = new StringBuilder();
        for (String part : parts) {
            if (!part.isBlank()) {
                initials.append(part.charAt(0));
            }
            if (initials.length() >= 6) {
                break;
            }
        }
        if (initials.length() >= 2) {
            return initials.toString();
        }
        String compact = text.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (compact.length() >= 2) {
            return compact.substring(0, Math.min(6, compact.length()));
        }
        if (compact.length() == 1) {
            return compact + "X";
        }
        return "RL";
    }

    private long nextSequence(String resourceKind, String abbreviation, String date) {
        String resourceKey = "business-id:%s:%s:%s".formatted(resourceKind, abbreviation, date);
        if (fencingProperties.isRedisEnabled()) {
            StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
            if (redisTemplate == null) {
                throw PlatformException.conflict("REDIS_UNAVAILABLE",
                        "Redis business ID sequence is enabled but no Redis client is available",
                        Map.of("resourceKey", resourceKey));
            }
            Long value = redisTemplate.opsForValue()
                    .increment(fencingProperties.getKeyPrefix() + resourceKey);
            if (value == null) {
                throw PlatformException.conflict("REDIS_SEQUENCE_FAILED",
                        "Redis did not return a business ID sequence", Map.of("resourceKey", resourceKey));
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
            throw PlatformException.conflict("BUSINESS_ID_SEQUENCE_FAILED",
                    "Database did not return a business ID sequence", Map.of("resourceKey", resourceKey));
        }
        return value;
    }

    private void upsertDbSequence(String resourceKey, long value) {
        int updated = businessIdMapper.updateSequenceValue(resourceKey, value, Timestamp.from(clock.instant()));
        if (updated == 0) {
            try {
                businessIdMapper.insertSequence(resourceKey, value, Timestamp.from(clock.instant()));
            } catch (DuplicateKeyException ignored) {
                businessIdMapper.updateSequenceAtLeast(resourceKey, value, Timestamp.from(clock.instant()));
            }
        }
    }
}
