package com.example.kairo.platform.health;

import com.example.kairo.platform.fencing.FencingProperties;
import com.example.kairo.platform.fencing.FencingTokenService;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.7 M1-F &sect;8.6 item 2: Redis fencing failure behaviour. Redis is pointed at a closed port
 * (a real unreachable Redis, not a mock). The {@link FencingProperties} bean is toggled at runtime so
 * the two regimes share one context:
 * <ul>
 *   <li>Redis fencing <strong>disabled</strong>: readiness is unaffected (the {@code redis}
 *       contributor reports disabled/UP), and a fencing token is issued from the database sequence.</li>
 *   <li>Redis fencing <strong>enabled</strong> and Redis unreachable: a fencing write fails closed as
 *       the structured {@code REDIS_UNAVAILABLE} error, no token row is persisted (the authoritative
 *       PostgreSQL state is never lost), and readiness goes DOWN.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_redis_fence;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        // A real unreachable Redis: nothing listens on 127.0.0.1:1, so Lettuce fails fast (ECONNREFUSED).
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=1s",
        "kairo.platform.fencing.redis-enabled=false",
        "kairo.platform.rollout.scheduler.enabled=false",
        "kairo.platform.reconciliation.scheduler.enabled=false",
        "kairo.platform.runtime-lease.initial-delay-ms=999999",
        "kairo.platform.runtime-lease.fixed-delay-ms=999999",
        "kairo.platform.runtime-cleanup.initial-delay-ms=999999",
        "kairo.platform.runtime-cleanup.fixed-delay-ms=999999",
        "kairo.platform.script.expiry.initial-delay-ms=999999",
        "kairo.platform.script.expiry.fixed-delay-ms=999999",
        "kairo.platform.automation.expiry.initial-delay-ms=999999",
        "kairo.platform.automation.expiry.fixed-delay-ms=999999"
})
@ActiveProfiles("test")
class RedisFencingFailureIntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired FencingTokenService fencingTokenService;
    @Autowired FencingProperties fencingProperties;

    private RequestContext context;
    private boolean originalRedisEnabled;

    @BeforeEach
    void setUp() {
        context = new RequestContext("system", "corr-" + UUID.randomUUID(), "127.0.0.1", "header-dev", "test");
        originalRedisEnabled = fencingProperties.isRedisEnabled();
        fencingProperties.setRedisEnabled(false);
    }

    @AfterEach
    void tearDown() {
        fencingProperties.setRedisEnabled(originalRedisEnabled);
    }

    @Test
    void redisDisabledDoesNotAffectReadinessAndIssuesFromDatabase() {
        // Redis is unreachable (port 1) but fencing is disabled: readiness must stay UP.
        awaitHealth("readiness", 200, "UP");

        // A fencing token is issued from the authoritative database sequence, not Redis.
        String resourceId = "res-disabled-" + UUID.randomUUID();
        var issued = fencingTokenService.issue(context, "rule", resourceId, "test", 0L);
        assertThat(issued.get("redisBacked")).isEqualTo(false);
        assertThat(tokenCount(resourceId)).isEqualTo(1);
    }

    @Test
    void redisEnabledAndUnavailableFailsClosedAndDrivesReadinessDown() {
        String resourceId = "res-enabled-" + UUID.randomUUID();

        // Enable Redis fencing against the unreachable Redis: the write must fail as the structured
        // REDIS_UNAVAILABLE error, and no token row may be persisted (DB state not lost).
        fencingProperties.setRedisEnabled(true);
        assertThatThrownBy(() -> fencingTokenService.issue(context, "rule", resourceId, "test", 0L))
                .isInstanceOfSatisfying(PlatformException.class,
                        ex -> assertThat(ex.code()).isEqualTo("REDIS_UNAVAILABLE"));
        assertThat(tokenCount(resourceId))
                .as("authoritative DB state not lost: no fencing_token row for the failed write")
                .isZero();

        // Readiness is DOWN because the redis contributor pings the unreachable Redis.
        awaitHealth("readiness", 503, "DOWN");

        // Liveness is untouched (a Redis outage must not kill Platform liveness).
        awaitHealth("liveness", 200, "UP");
    }

    private int tokenCount(String resourceId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from fencing_token where resource_id = ?", Integer.class, resourceId);
        return count == null ? 0 : count;
    }

    private void awaitHealth(String group, int expectedStatus, String bodyMarker) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                ResponseEntity<String> resp = http.getForEntity("/actuator/health/" + group, String.class);
                boolean ok = resp.getStatusCode().value() == expectedStatus
                        && resp.getBody() != null && resp.getBody().contains(bodyMarker);
                if (ok) {
                    return;
                }
                last = new AssertionError(group + " => " + resp.getStatusCode() + " " + resp.getBody());
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted", e);
            }
        }
        throw new AssertionError("health /actuator/health/" + group + " never reached "
                + expectedStatus + "/" + bodyMarker + " (last: " + (last == null ? "null" : last.getMessage()) + ")", last);
    }
}
