package com.example.kairo.platform.health;

import com.example.kairo.platform.fencing.FencingProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * V1.7 M1-F &sect;8.6 item 2 / &sect;11.1: the readiness contributor for Redis. It is registered under the
 * {@code redis} contributor name (Spring's auto {@code RedisHealthIndicator} is disabled via
 * {@code management.health.redis.enabled=false}) so the readiness group is a fixed, deterministic set
 * {@code db,redis}. Flyway validation is enforced during startup by the schema compatibility guard;
 * the later M4 operational milestone owns any additional runtime Flyway health contract.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>Redis fencing <strong>disabled</strong> (the default): the contributor reports UP with a
 *       {@code disabled} detail. Redis can never affect readiness when it is not in use (&sect;8.6 item 2
 *       first half). No Redis connection is attempted, so a down/unreachable Redis is invisible.</li>
 *   <li>Redis fencing <strong>enabled</strong>: the contributor pings Redis on each probe. An
 *       unreachable Redis drives readiness DOWN so traffic is routed away, while the authoritative
 *       PostgreSQL state is never lost (fencing writes fail closed with {@code REDIS_UNAVAILABLE} in
 *       {@link com.example.kairo.platform.fencing.FencingTokenService}).</li>
 * </ul>
 *
 * <p>Liveness is intentionally untouched: a Redis outage must not kill Platform liveness (&sect;11.1).
 */
@Component("redis")
public class KairoRedisHealthIndicator implements HealthIndicator {

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final FencingProperties properties;

    public KairoRedisHealthIndicator(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                     FencingProperties properties) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.properties = properties;
    }

    @Override
    public Health health() {
        if (!properties.isRedisEnabled()) {
            return Health.up().withDetail("redis", "disabled (fencing uses the database sequence)").build();
        }
        StringRedisTemplate redisTemplate = redisTemplateProvider.getIfAvailable();
        if (redisTemplate == null) {
            return Health.down().withDetail("error", "Redis fencing is enabled but no Redis client is available").build();
        }
        try {
            String reply = redisTemplate.execute((RedisCallback<String>) connection -> {
                connection.ping();
                return "PONG";
            });
            return Health.up().withDetail("reply", reply == null ? "PONG" : reply).build();
        } catch (RuntimeException e) {
            return Health.down(e).withDetail("error", String.valueOf(e.getMessage())).build();
        }
    }
}
