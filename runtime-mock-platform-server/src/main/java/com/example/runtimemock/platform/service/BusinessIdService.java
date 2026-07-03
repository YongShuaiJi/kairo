package com.example.runtimemock.platform.service;

import com.example.runtimemock.platform.fencing.FencingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final FencingProperties fencingProperties;
    private final Clock clock;

    @Autowired
    public BusinessIdService(JdbcTemplate jdbcTemplate,
                             ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                             FencingProperties fencingProperties) {
        this(jdbcTemplate, redisTemplateProvider, fencingProperties, Clock.systemDefaultZone());
    }

    BusinessIdService(JdbcTemplate jdbcTemplate,
                      ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                      FencingProperties fencingProperties,
                      Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
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
            throw PlatformException.conflict("BUSINESS_ID_SEQUENCE_FAILED",
                    "Database did not return a business ID sequence", Map.of("resourceKey", resourceKey));
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
            try {
                jdbcTemplate.update("""
                        insert into fencing_sequence(resource_key, current_value, updated_at)
                        values (?, ?, ?)
                        """, resourceKey, value, Timestamp.from(clock.instant()));
            } catch (DuplicateKeyException ignored) {
                jdbcTemplate.update("""
                        update fencing_sequence
                           set current_value = greatest(current_value, ?), updated_at = ?
                         where resource_key = ?
                        """, value, Timestamp.from(clock.instant()), resourceKey);
            }
        }
    }
}
