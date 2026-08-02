package com.example.kairo.platform.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M4-A &sect;11.1: the health-group composition guard plus real DB/Flyway failure-and-recovery.
 *
 * <ul>
 *   <li>Liveness ({@code /actuator/health/liveness}) is exclusively {@code livenessState}: it must never
 *       contain {@code db}/{@code flyway}/{@code redis}, and a DB outage must leave it 200/UP. Fails if a
 *       dependency contributor is ever accidentally added to the liveness group.</li>
 *   <li>Readiness ({@code /actuator/health/readiness}) must contain {@code readinessState}, {@code db},
 *       {@code flyway} and {@code redis} (disabled/UP when fencing is off). Fails if any is omitted.</li>
 *   <li>A real DB outage (a {@link ToggleableDataSource} that throws on {@code getConnection()}) drives
 *       readiness DOWN with both {@code db} and {@code flyway} DOWN, and both recover when the connection
 *       returns. {@code flyway} is a real Flyway validate that never migrates from the health request.</li>
 * </ul>
 *
 * <p>Redis fencing is left disabled (the default) so the {@code redis} contributor is an inert UP/disabled
 * member of the readiness group; its recovery behaviour is covered by
 * {@code RedisHealthRecoveryIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_health_grp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
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
class HealthGroupRecoveryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    TestRestTemplate http;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ToggleableDataSource dataSource;
    @Autowired
    TestPlatformMapper fixtures;

    private String agentId;
    private String instanceId;

    @BeforeEach
    void setUp() {
        dataSource.setDown(false);
    }

    @AfterEach
    void tearDown() {
        dataSource.setDown(false);
        if (agentId != null) {
            jdbc.update("delete from agent_command where agent_id = ?", agentId);
            jdbc.update("delete from agent_instance where id = ?", agentId);
            agentId = null;
        }
        if (instanceId != null) {
            jdbc.update("delete from instance where id = ?", instanceId);
            instanceId = null;
        }
    }

    @Test
    void readinessContainsDbFlywayRedisAndLivenessIsProcessLivenessOnly() throws Exception {
        // Baseline: readiness UP with all four members; liveness UP with livenessState only.
        JsonNode readiness = awaitReadiness(200, "UP");
        assertThat(componentNames(readiness))
                .containsExactlyInAnyOrder("readinessState", "db", "flyway", "redis");
        assertThat(statusOf(readiness, "db")).isEqualTo("UP");
        assertThat(statusOf(readiness, "flyway")).isEqualTo("UP");
        assertThat(statusOf(readiness, "redis")).isEqualTo("UP");

        JsonNode liveness = awaitLiveness(200, "UP");
        assertThat(componentNames(liveness)).containsExactly("livenessState");

        // Flyway validate is read-only: a health probe must not apply or alter migrations.
        int historyBefore = flywayHistoryCount();

        // Outage: readiness DOWN with db and flyway DOWN; liveness still UP (process-liveness only).
        dataSource.setDown(true);
        JsonNode downReadiness = awaitReadiness(503, "DOWN");
        assertThat(statusOf(downReadiness, "db")).isEqualTo("DOWN");
        assertThat(statusOf(downReadiness, "flyway")).isEqualTo("DOWN");
        // Flyway emits only a stable classified message; no JDBC URL, password or stack.
        JsonNode flywayDetail = downReadiness.get("components").get("flyway").get("details");
        assertThat(flywayDetail.get("database").asText()).isEqualTo("unavailable");
        JsonNode livenessDuringOutage = awaitLiveness(200, "UP");
        assertThat(componentNames(livenessDuringOutage)).containsExactly("livenessState");
        assertNoSecretsOrStacks(downReadiness.toString());

        // Recovery: readiness UP again with db and flyway recovered.
        dataSource.setDown(false);
        JsonNode recovered = awaitReadiness(200, "UP");
        assertThat(statusOf(recovered, "db")).isEqualTo("UP");
        assertThat(statusOf(recovered, "flyway")).isEqualTo("UP");

        // No migration was applied/altered by the health probes (validate is read-only).
        int historyAfter = flywayHistoryCount();
        assertThat(historyAfter).isEqualTo(historyBefore);
    }

    @Test
    void agentOfflineDoesNotAffectLiveness() throws Exception {
        // A registered Agent going OFFLINE is application/data state, not process state. Liveness is
        // exclusively process-liveness (livenessState only) and the platform publishes no
        // AvailabilityChangeEvent from agent state, so an offline Agent must leave liveness UP and must
        // not add any agent component to the liveness group.
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        instanceId = "inst-liveness-" + UUID.randomUUID();
        agentId = "agent-liveness-" + UUID.randomUUID();
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, created_at, updated_at)
                values (?, 'app-default', 'env-dev', ?, 'localhost', '1', 'java', 'ACTIVE', '{}',
                  current_timestamp, current_timestamp)
                """, instanceId, instanceId);
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values (?, ?, 'OFFLINE', 'test', 'test', '127.0.0.1', 1, 'hash-only', '[]',
                  current_timestamp, current_timestamp)
                """, agentId, instanceId);

        // Liveness stays UP and remains process-liveness only (no agent/db/flyway/redis component).
        JsonNode liveness = awaitLiveness(200, "UP");
        assertThat(componentNames(liveness)).containsExactly("livenessState");

        // Readiness is also unaffected by the offline Agent: still the same four members, all UP
        // (no agent contributor exists), so an offline Agent cannot route traffic away by itself.
        JsonNode readiness = awaitReadiness(200, "UP");
        assertThat(componentNames(readiness))
                .containsExactlyInAnyOrder("readinessState", "db", "flyway", "redis");
    }

    @Test
    void flywayChecksumDriftDropsReadinessWhileDatabaseAndLivenessStayUpThenRecovers() {
        MigrationChecksum latest = jdbc.queryForObject("""
                select installed_rank, checksum
                from flyway_schema_history
                where success = true and checksum is not null
                order by installed_rank desc
                limit 1
                """, (rs, rowNum) -> new MigrationChecksum(
                rs.getInt("installed_rank"), rs.getInt("checksum")));
        assertThat(latest).isNotNull();

        try {
            jdbc.update("update flyway_schema_history set checksum = ? where installed_rank = ?",
                    latest.checksum() + 1, latest.installedRank());

            JsonNode drifted = awaitReadiness(503, "DOWN");
            assertThat(statusOf(drifted, "db")).isEqualTo("UP");
            assertThat(statusOf(drifted, "flyway")).isEqualTo("DOWN");
            assertThat(componentNames(awaitLiveness(200, "UP")))
                    .containsExactly("livenessState");
        } finally {
            jdbc.update("update flyway_schema_history set checksum = ? where installed_rank = ?",
                    latest.checksum(), latest.installedRank());
        }

        JsonNode recovered = awaitReadiness(200, "UP");
        assertThat(statusOf(recovered, "db")).isEqualTo("UP");
        assertThat(statusOf(recovered, "flyway")).isEqualTo("UP");
    }

    private int flywayHistoryCount() {
        Integer count = jdbc.queryForObject("select count(*) from flyway_schema_history", Integer.class);
        return count == null ? 0 : count;
    }

    private JsonNode awaitReadiness(int expectedStatus, String bodyMarker) {
        return await("readiness", expectedStatus, bodyMarker);
    }

    private JsonNode awaitLiveness(int expectedStatus, String bodyMarker) {
        return await("liveness", expectedStatus, bodyMarker);
    }

    private JsonNode await(String group, int expectedStatus, String bodyMarker) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
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
            sleepQuietly(50L);
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

    private record MigrationChecksum(int installedRank, int checksum) {
    }

    /** Replaces the primary DataSource with a toggleable wrapper so a real outage can be toggled. */
    @TestConfiguration
    static class ToggleableDataSourceConfig {
        @Bean
        @Primary
        ToggleableDataSource toggleableDataSource(@Value("${spring.datasource.url}") String url) {
            JdbcDataSource h2 = new JdbcDataSource();
            h2.setURL(url);
            h2.setUser("sa");
            h2.setPassword("");
            return new ToggleableDataSource(h2);
        }
    }
}
