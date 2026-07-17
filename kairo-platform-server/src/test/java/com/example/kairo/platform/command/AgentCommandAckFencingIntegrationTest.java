package com.example.kairo.platform.command;

import com.example.kairo.platform.persistence.mapper.AgentCommandMapper;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.BusinessIdService;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.7 M1-A &sect;8.1: the ack fencing contract. The ACK SQL guards on {@code status=DISPATCHED}
 * AND the dispatch epoch ({@code attempts}); a stale, future or (post-redispatch) missing epoch
 * matches zero rows and fails closed with {@code AGENT_COMMAND_STATE_CONFLICT} rather than letting a
 * stale owner overwrite the new owner's state. The V1.6 wire contract is preserved only on the
 * first dispatch: a missing epoch is accepted when {@code attempts == 1} (exactly one owner could
 * have produced the ack) and rejected thereafter.
 *
 * <p>Terminal state is immutable: a duplicate/delayed ack returns a conflict and never re-runs
 * rollout/unload or records a second audit event. Time is driven by a {@link MutableClock} (no
 * sleeps); a dedicated in-memory H2 isolates these tests.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1a;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class AgentCommandAckFencingIntegrationTest {

    @Autowired AgentCommandMapper commandMapper;
    @Autowired RbacService rbacService;
    @Autowired PlatformCoreService eventWriter;
    @Autowired BusinessIdService businessIdService;
    @Autowired CapabilityGate capabilityGate;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;

    private MutableClock clock;
    private AgentCommandService commands;
    private String agentId;
    private String instanceId;
    private RequestContext admin;
    private RequestContext agentCtx;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        agentId = "agent-ack-" + UUID.randomUUID();
        instanceId = "inst-ack-" + UUID.randomUUID();
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
        clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        commands = new AgentCommandService(commandMapper, rbacService, eventWriter,
                businessIdService, clock, capabilityGate);
        admin = new RequestContext("system", "corr", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr", "127.0.0.1", "agent", "test");
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
    }

    @Test
    void currentEpochAckSucceeds() {
        String id = createCommand();
        poll(); // attempts=1
        Map<String, Object> acked = commands.ack(id, agentCtx, ackRequest("ACKED", 1,
                Map.of("disabled", true), null));
        assertThat(acked.get("status")).isEqualTo("ACKED");
        assertThat(commandStatus(id)).isEqualTo("ACKED");
    }

    @Test
    void staleEpochAckIsFencedOut() {
        String id = createCommand();
        poll(); // attempts=1 (owner A)
        clock.advance(Duration.ofSeconds(61));
        poll(); // re-dispatch -> attempts=2 (owner B); owner A's lease was reclaimed
        // owner A acks with its stale epoch 1 -> fenced out, command left to owner B
        assertThatThrownBy(() -> commands.ack(id, agentCtx, ackRequest("ACKED", 1, Map.of(), null)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> {
                    PlatformException pe = (PlatformException) t;
                    assertThat(pe.code()).isEqualTo("AGENT_COMMAND_STATE_CONFLICT");
                    assertThat(pe.details()).containsEntry("expectedAttempts", 1L);
                    assertThat(((Number) pe.details().get("currentAttempts")).longValue()).isEqualTo(2L);
                });
        assertThat(commandStatus(id)).isEqualTo("DISPATCHED");
        assertThat(commandAttempts(id)).isEqualTo(2L);
    }

    @Test
    void futureEpochAckIsFencedOut() {
        String id = createCommand();
        poll();
        clock.advance(Duration.ofSeconds(61));
        poll(); // attempts=2
        // an epoch that was never dispatched cannot ack (no owner ever held it)
        assertThatThrownBy(() -> commands.ack(id, agentCtx, ackRequest("ACKED", 3, Map.of(), null)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code())
                        .isEqualTo("AGENT_COMMAND_STATE_CONFLICT"));
        assertThat(commandStatus(id)).isEqualTo("DISPATCHED");
        assertThat(commandAttempts(id)).isEqualTo(2L);
    }

    @Test
    void missingEpochOnRedispatchIsFencedOut() {
        String id = createCommand();
        poll(); // attempts=1
        clock.advance(Duration.ofSeconds(61));
        poll(); // attempts=2 (re-dispatched) -> §4.3: a missing epoch is ambiguous after redispatch
        // V1.6 legacy ack without expectedAttempts: only attempts==1 is accepted.
        assertThatThrownBy(() -> commands.ack(id, agentCtx, legacyAckRequest("ACKED", Map.of(), null)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> {
                    PlatformException pe = (PlatformException) t;
                    assertThat(pe.code()).isEqualTo("AGENT_COMMAND_STATE_CONFLICT");
                    assertThat(pe.details()).containsEntry("expectedAttempts", null);
                    assertThat(((Number) pe.details().get("currentAttempts")).longValue()).isEqualTo(2L);
                });
        assertThat(commandStatus(id)).isEqualTo("DISPATCHED");
    }

    @Test
    void legacyFirstDispatchAckWithoutEpochSucceeds() {
        String id = createCommand();
        poll(); // attempts=1 -> §4.3: missing epoch accepted only on the first dispatch
        Map<String, Object> acked = commands.ack(id, agentCtx,
                legacyAckRequest("ACKED", Map.of("disabled", true), null));
        assertThat(acked.get("status")).isEqualTo("ACKED");
        assertThat(commandStatus(id)).isEqualTo("ACKED");
    }

    @Test
    void fractionalEpochIsRejectedInsteadOfBeingTruncated() {
        String id = createCommand();
        poll();
        Map<String, Object> malformed = new LinkedHashMap<>();
        malformed.put("status", "ACKED");
        malformed.put("expectedAttempts", 1.5d);
        malformed.put("result", Map.of());
        assertThatThrownBy(() -> commands.ack(id, agentCtx, malformed))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(id)).isEqualTo("DISPATCHED");
    }

    @Test
    void nonNumericEpochIsRejectedAsInvalidField() {
        String id = createCommand();
        poll();
        Map<String, Object> malformed = new LinkedHashMap<>();
        malformed.put("status", "ACKED");
        malformed.put("expectedAttempts", "owner-one");
        malformed.put("result", Map.of());
        assertThatThrownBy(() -> commands.ack(id, agentCtx, malformed))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(id)).isEqualTo("DISPATCHED");
    }

    @Test
    void ackedTerminalIsImmutable() {
        String id = createCommand();
        poll();
        commands.ack(id, agentCtx, ackRequest("ACKED", 1, Map.of("disabled", true), null));
        // a duplicate ack on a terminal command does not re-advance; it conflicts.
        assertThatThrownBy(() -> commands.ack(id, agentCtx, ackRequest("ACKED", 1, Map.of(), null)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code())
                        .isEqualTo("AGENT_COMMAND_STATE_CONFLICT"));
        assertThat(commandStatus(id)).isEqualTo("ACKED");
    }

    @Test
    void failedTerminalIsImmutable() {
        String id = createCommand();
        poll();
        commands.ack(id, agentCtx, ackRequest("FAILED", 1, Map.of(), "boom"));
        assertThat(commandStatus(id)).isEqualTo("FAILED");
        assertThatThrownBy(() -> commands.ack(id, agentCtx, ackRequest("ACKED", 1, Map.of(), null)))
                .isInstanceOf(PlatformException.class);
        assertThat(commandStatus(id)).isEqualTo("FAILED");
    }

    @Test
    void duplicateAckDoesNotReAdvanceAudit() {
        String id = createCommand();
        poll();
        commands.ack(id, agentCtx, ackRequest("ACKED", 1, Map.of("disabled", true), null));
        long ackEvents = ackEventCount(id);
        assertThat(ackEvents).isEqualTo(1L);
        // the duplicate is fenced out before recordEvent, so audit is not advanced a second time.
        assertThatThrownBy(() -> commands.ack(id, agentCtx, ackRequest("ACKED", 1, Map.of(), null)))
                .isInstanceOf(PlatformException.class);
        assertThat(ackEventCount(id)).isEqualTo(ackEvents);
    }

    // -------------------------------------------------------- helpers

    private String createCommand() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", "DISABLE_ALL");
        request.put("maxAttempts", 5);
        Map<String, Object> created = commands.createManualCommand(admin, agentId, request);
        return String.valueOf(created.get("id"));
    }

    private Map<String, Object> poll() {
        return commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
    }

    /** A V1.7 ack that echoes the dispatch epoch as the fencing token. */
    private Map<String, Object> ackRequest(String status, long expectedAttempts,
                                           Map<String, Object> result, String errorMessage) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", status);
        request.put("expectedAttempts", expectedAttempts);
        request.put("result", result);
        if (errorMessage != null) {
            request.put("errorMessage", errorMessage);
        }
        return request;
    }

    /** A legacy V1.6 ack that does NOT carry the epoch (null expectedAttempts). */
    private Map<String, Object> legacyAckRequest(String status, Map<String, Object> result, String errorMessage) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", status);
        request.put("result", result);
        if (errorMessage != null) {
            request.put("errorMessage", errorMessage);
        }
        return request;
    }

    private String commandStatus(String id) {
        return jdbc.queryForObject("select status from agent_command where id = ?", String.class, id);
    }

    private long commandAttempts(String id) {
        return jdbc.queryForObject("select attempts from agent_command where id = ?",
                Number.class, id).longValue();
    }

    private long ackEventCount(String commandId) {
        Long count = jdbc.queryForObject(
                "select count(*) from audit_record where action = 'agent_command.ack' and resource_id = ?",
                Long.class, commandId);
        return count == null ? 0L : count;
    }
}
