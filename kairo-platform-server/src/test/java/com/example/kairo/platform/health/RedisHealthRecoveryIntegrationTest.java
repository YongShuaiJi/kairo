package com.example.kairo.platform.health;

import com.example.kairo.platform.fencing.FencingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M4-A &sect;11.1: real Redis readiness failure <em>and recovery</em> through the real Lettuce client,
 * against a real TCP Redis-compatible endpoint ({@link EmbeddedRedisServer}) that can be stopped and restarted
 * on the same configured address. A fixed permanently closed port (like 127.0.0.1:1, used by
 * {@code RedisFencingFailureIntegrationTest} for the fail-closed write contract) cannot be restarted, so it
 * cannot prove recovery; a mocked {@code HealthIndicator} proves nothing. This test proves both.
 *
 * <p>With Redis fencing <strong>enabled</strong>, the {@code redis} readiness contributor pings on each probe,
 * so stopping the server drives readiness DOWN (redis DOWN) while liveness stays UP, and restarting the server
 * on the same port drives readiness UP again. With fencing <strong>disabled</strong>, an unreachable Redis is
 * irrelevant to readiness (the contributor is an inert UP/disabled member that never connects).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_redis_health;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "kairo.platform.fencing.redis-enabled=true",
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
class RedisHealthRecoveryIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static EmbeddedRedisServer redis;

    @DynamicPropertySource
    static void registerRedis(DynamicPropertyRegistry registry) throws Exception {
        redis = new EmbeddedRedisServer();
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.port()));
        registry.add("spring.data.redis.timeout", () -> "1s");
    }

    @AfterAll
    static void stopRedis() {
        if (redis != null) {
            redis.stop();
        }
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    FencingProperties fencingProperties;

    private boolean originalRedisEnabled;

    @BeforeEach
    void setUp() {
        originalRedisEnabled = fencingProperties.isRedisEnabled();
        fencingProperties.setRedisEnabled(true);
    }

    @AfterEach
    void tearDown() {
        fencingProperties.setRedisEnabled(originalRedisEnabled);
        // Leave the embedded server running again for the next test.
        if (redis != null && !redis.isRunning()) {
            try {
                redis.restart();
            } catch (Exception ignored) {
                // best-effort; @AfterAll finalises
            }
        }
    }

    @Test
    void stoppingAndRestartingRealRedisDrivesReadinessDownThenUp() throws Exception {
        // Baseline: redis UP, readiness UP with redis as a member; liveness UP with livenessState only.
        JsonNode readiness = awaitReadiness(200, "UP");
        assertThat(componentNames(readiness))
                .containsExactlyInAnyOrder("readinessState", "db", "flyway", "redis");
        assertThat(statusOf(readiness, "redis")).isEqualTo("UP");
        assertThat(componentNames(awaitLiveness(200, "UP"))).containsExactly("livenessState");

        // Stop the real Redis endpoint: readiness DOWN (redis DOWN), liveness still UP.
        redis.stop();
        JsonNode down = awaitReadiness(503, "DOWN");
        assertThat(statusOf(down, "redis")).isEqualTo("DOWN");
        JsonNode livenessDown = awaitLiveness(200, "UP");
        assertThat(componentNames(livenessDown)).containsExactly("livenessState");
        assertNoSecretsOrStacks(down.toString());

        // Restart on the same configured address: readiness recovers (redis UP).
        redis.restart();
        JsonNode recovered = awaitReadiness(200, "UP");
        assertThat(statusOf(recovered, "redis")).isEqualTo("UP");
    }

    @Test
    void optionalRedisIsIrrelevantToReadiness() throws Exception {
        // With fencing disabled, Redis is not required: even an unreachable Redis must not affect readiness.
        fencingProperties.setRedisEnabled(false);
        redis.stop();

        JsonNode readiness = awaitReadiness(200, "UP");
        assertThat(statusOf(readiness, "redis")).isEqualTo("UP");
        // Liveness is unaffected and remains process-liveness only.
        assertThat(componentNames(awaitLiveness(200, "UP"))).containsExactly("livenessState");

        // Restore fencing + a reachable endpoint for tearDown.
        redis.restart();
        fencingProperties.setRedisEnabled(true);
    }

    private JsonNode awaitReadiness(int expectedStatus, String bodyMarker) {
        return await("readiness", expectedStatus, bodyMarker);
    }

    private JsonNode awaitLiveness(int expectedStatus, String bodyMarker) {
        return await("liveness", expectedStatus, bodyMarker);
    }

    private JsonNode await(String group, int expectedStatus, String bodyMarker) {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                ResponseEntity<String> resp = http.getForEntity("/actuator/health/" + group, String.class);
                if (resp.getStatusCode().value() == expectedStatus
                        && resp.getBody() != null && resp.getBody().contains(bodyMarker)) {
                    return MAPPER.readTree(resp.getBody());
                }
                last = new AssertionError(group + " => " + resp.getStatusCode() + " " + resp.getBody());
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                last = e;
            }
            sleepQuietly(100L);
        }
        throw new AssertionError("health /actuator/health/" + group + " never reached "
                + expectedStatus + "/" + bodyMarker + " (last: " + (last == null ? "null" : last.getMessage()) + ")", last);
    }

    private static List<String> componentNames(JsonNode health) {
        JsonNode components = health.get("components");
        List<String> names = new ArrayList<>();
        Iterator<String> it = components.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    private static String statusOf(JsonNode health, String component) {
        return health.get("components").get(component).get("status").asText();
    }

    private static void assertNoSecretsOrStacks(String body) {
        String lower = body.toLowerCase();
        assertThat(lower).doesNotContain("password", "secret", "authorization",
                "jdbc:", "stacktrace", "at com.", "at org.springframework");
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
