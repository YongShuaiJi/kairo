package com.example.kairo.platform.health;

import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.service.RequestContext;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-F &sect;8.6 item 1: a short PostgreSQL outage must drive readiness DOWN while liveness stays
 * UP, and once the connection returns an unfinished DURABLE command must continue to be processed.
 *
 * <p>The outage is simulated by a real connection-level failure (a {@link ToggleableDataSource} that
 * throws {@code SQLException} on {@code getConnection()}, exactly as an unreachable database would),
 * never by mocking a fixed health indicator. Spring's {@code db} readiness probe really fails; the
 * {@code liveness} probe (livenessState only) stays UP. A DURABLE command enqueued before the outage
 * is still PENDING after recovery and is then dispatched and acked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_dep_health;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
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
class DependencyHealthRecoveryIntegrationTest {

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired AgentCommandService commands;
    @Autowired TestPlatformMapper fixtures;
    @Autowired ToggleableDataSource dataSource;

    private String agentId;
    private String instanceId;
    private RequestContext admin;
    private RequestContext agentCtx;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        agentId = "agent-dephealth-" + UUID.randomUUID();
        instanceId = "inst-dephealth-" + UUID.randomUUID();
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, created_at, updated_at)
                values (?, 'app-default', 'env-dev', ?, 'localhost', '1', 'java', 'ACTIVE', '{}',
                  current_timestamp, current_timestamp)
                """, instanceId, instanceId);
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values (?, ?, 'ACTIVE', 'test', 'test', '127.0.0.1', 1, 'hash-only', '[]',
                  current_timestamp, current_timestamp)
                """, agentId, instanceId);
        admin = new RequestContext("system", "corr", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr", "127.0.0.1", "agent", "test");
        dataSource.setDown(false);
    }

    @AfterEach
    void tearDown() {
        if (agentId != null) {
            jdbc.update("delete from agent_command where agent_id = ?", agentId);
            jdbc.update("delete from agent_instance where id = ?", agentId);
        }
        if (instanceId != null) {
            jdbc.update("delete from instance where id = ?", instanceId);
        }
        dataSource.setDown(false);
    }

    @Test
    void postgresOutageDropsReadinessKeepsLivenessAndDurableCommandContinues() {
        // A DURABLE command enqueued while the DB is up.
        String commandId = createDurableCommand("DISABLE_ALL", 5);
        assertThat(commandStatus(commandId)).isEqualTo("PENDING");

        // Baseline: readiness and liveness both UP.
        awaitHealth("readiness", 200, "UP");
        awaitHealth("liveness", 200, "UP");

        // Outage: readiness DOWN, liveness UP.
        dataSource.setDown(true);
        awaitHealth("readiness", 503, "DOWN");
        awaitHealth("liveness", 200, "UP");

        // Recovery: readiness UP again.
        dataSource.setDown(false);
        awaitHealth("readiness", 200, "UP");

        // The unfinished DURABLE command survived the outage and continues processing: it is still
        // PENDING, then dispatched (PENDING -> DISPATCHED) and acked (DISPATCHED -> ACKED).
        assertThat(commandStatus(commandId)).isEqualTo("PENDING");
        Map<String, Object> polled = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        assertThat(polled.get("status")).isEqualTo("DISPATCHED");
        long attempts = ((Number) polled.get("attempts")).longValue();
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", "ACKED");
        ack.put("expectedAttempts", attempts);
        commands.ack(commandId, agentCtx, ack);
        assertThat(commandStatus(commandId)).isEqualTo("ACKED");
    }

    private String createDurableCommand(String type, long maxAttempts) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", type);
        request.put("maxAttempts", maxAttempts);
        return String.valueOf(commands.createManualCommand(admin, agentId, request).get("id"));
    }

    private String commandStatus(String id) {
        return jdbc.queryForObject("select status from agent_command where id = ?", String.class, id);
    }

    /** Poll a health endpoint until it matches the expected status code and body marker (or fails). */
    private void awaitHealth(String group, int expectedStatus, String bodyMarker) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        Throwable last = null;
        while (System.nanoTime() < deadline) {
            try {
                ResponseEntity<String> resp = http.getForEntity("/actuator/health/" + group, String.class);
                boolean statusMatches = resp.getStatusCode().value() == expectedStatus;
                boolean bodyMatches = resp.getBody() != null && resp.getBody().contains(bodyMarker);
                if (statusMatches && bodyMatches) {
                    return;
                }
                last = new AssertionError("health " + group + " => " + resp.getStatusCode() + " " + resp.getBody());
            } catch (RuntimeException e) {
                last = e;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted awaiting health " + group, e);
            }
        }
        throw new AssertionError("health /actuator/health/" + group + " never reached "
                + expectedStatus + "/" + bodyMarker + " (last: " + (last == null ? "null" : last.getMessage()) + ")", last);
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
