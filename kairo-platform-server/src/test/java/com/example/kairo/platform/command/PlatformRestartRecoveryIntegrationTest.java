package com.example.kairo.platform.command;

import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-B &sect;8.2 (DURABLE): a Platform restart against the same persistent database keeps
 * PENDING DURABLE commands claimable and expired DISPATCHED DURABLE commands redispatchable
 * under M1-A, while the startup recovery never fails or re-dispatches them.
 *
 * <p>Each test launches real {@link com.example.kairo.platform.KairoPlatformApplication} contexts
 * against a per-method file-backed H2 (no context reuse, no method-call-only restart). Time is
 * deterministic: lease expiry is simulated by overwriting {@code lease_expires_at} to a fixed
 * past timestamp, never by sleeping.
 */
class PlatformRestartRecoveryIntegrationTest {

    @TempDir
    Path tempDir;

    private RestartRecoveryHarness harness;

    @BeforeEach
    void setUp() {
        harness = new RestartRecoveryHarness(tempDir);
    }

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    @Test
    void pendingDurableCommandRemainsClaimableAfterRestart() {
        harness.start();
        String agentId = seed();
        String id = createDurable(agentId, "DISABLE_ALL", 5);
        assertThat(status(id)).isEqualTo("PENDING");
        harness.stop();

        // Restart against the same H2 file: the startup recovery runs but DURABLE commands are
        // left untouched, so the PENDING command is still claimable by the agent's next poll.
        harness.start();
        Map<String, Object> polled = poll(agentId);
        assertThat(polled.get("status")).isEqualTo("DISPATCHED");
        assertThat(polled.get("id")).isEqualTo(id);
        // §8.2#2: claimable -> a fresh dispatch epoch (attempts = 1), not a replay.
        assertThat(((Number) polled.get("attempts")).longValue()).isEqualTo(1L);
        assertThat(status(id)).isEqualTo("DISPATCHED");
        assertThat(errorMessage(id)).isNull();
    }

    @Test
    void expiredDispatchedDurableCommandIsRedispatchableAfterRestart() {
        harness.start();
        String agentId = seed();
        String id = createDurable(agentId, "DISABLE_ALL", 5);
        // First dispatch (attempts=1), then deterministically expire the lease (no sleep).
        Map<String, Object> first = poll(agentId);
        assertThat(((Number) first.get("attempts")).longValue()).isEqualTo(1L);
        RestartRecoveryHarness.expireLease(harness.jdbc(), id);
        assertThat(status(id)).isEqualTo("DISPATCHED");
        harness.stop();

        // §8.2#3: after restart the expired DISPATCHED DURABLE command is redispatchable under
        // M1-A -- the next poll reclaims it with a new epoch (attempts = 2), not a terminal fail.
        harness.start();
        Map<String, Object> reclaimed = poll(agentId);
        assertThat(reclaimed.get("status")).isEqualTo("DISPATCHED");
        assertThat(reclaimed.get("id")).isEqualTo(id);
        assertThat(((Number) reclaimed.get("attempts")).longValue()).isEqualTo(2L);
        assertThat(status(id)).isEqualTo("DISPATCHED");
        assertThat(errorMessage(id)).isNull();
    }

    @Test
    void everyDurableFamilyStaysClaimableAfterRestart() {
        // §4.2: every fixed DURABLE command family must survive a restart still claimable
        // (recovery fails only TRANSIENT orphans; DURABLE PENDING rows are never matched).
        List<String> durable = List.of(
                "APPLY_RULE", "APPLY_CHAIN", "DISABLE_ALL", "ENABLE_ALL",
                "RESET_CLASS", "RESET_ALL", "STOP_AGENT", "REFRESH_RUNTIME_STATE");
        harness.start();
        String agentId = seed();
        List<String> ids = new ArrayList<>();
        for (String type : durable) {
            ids.add(createDurable(agentId, type, 5));
        }
        // Sanity: all enqueued as PENDING before the restart.
        for (String id : ids) {
            assertThat(status(id)).isEqualTo("PENDING");
        }
        harness.stop();

        harness.start();
        // Recovery ran at startup; no DURABLE command was failed or advanced.
        for (String id : ids) {
            assertThat(status(id))
                    .as("DURABLE command %s must stay PENDING across restart", id).isEqualTo("PENDING");
            assertThat(errorMessage(id)).isNull();
        }
        // Each is claimable: eight polls each return a distinct DISPATCHED command (attempts = 1).
        // The capability gate accepts all eight (legacy agent -> V1 set).
        int dispatched = 0;
        for (int i = 0; i < durable.size(); i++) {
            Map<String, Object> polled = poll(agentId);
            if ("DISPATCHED".equals(polled.get("status"))) {
                dispatched++;
                assertThat(((Number) polled.get("attempts")).longValue()).isEqualTo(1L);
            }
        }
        assertThat(dispatched).isEqualTo(durable.size());
        // No command left to claim.
        assertThat(poll(agentId).get("status")).isEqualTo("NO_COMMAND");
    }

    @Test
    void restartRecoveryIsIdempotentAcrossStartups() {
        // §8.2#6: a second (and third) startup must not mutate already-recovered rows or duplicate
        // recovery audit evidence, and must never touch DURABLE commands. A TRANSIENT orphan is
        // included so the test proves the recovery actually ran on each startup (it is failed
        // once on the first recovery and never re-mutated), while the DURABLE command stays
        // claimable across all three startups.
        harness.start();
        String agentId = seed();
        String durableId = createDurable(agentId, "DISABLE_ALL", 5);
        String transientId = createTransient(agentId, "DISCOVER_TARGETS", 1);
        assertThat(recoveryAuditCount(transientId)).isZero();
        harness.stop();

        // First restart: recovery fails the orphan TRANSIENT once, leaves DURABLE PENDING.
        harness.start();
        assertThat(status(transientId)).isEqualTo("FAILED");
        assertThat(errorMessage(transientId)).isEqualTo(
                AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE);
        assertThat(status(durableId)).isEqualTo("PENDING");
        long transientAuditAfterFirst = recoveryAuditCount(transientId);
        assertThat(transientAuditAfterFirst).isEqualTo(1L);
        java.sql.Timestamp transientCompletedAt = completedAt(transientId);
        harness.stop();

        // Second restart: recovery is a no-op on the already-FAILED TRANSIENT (no duplicate
        // audit, no mutation) and still leaves the DURABLE command PENDING.
        harness.start();
        assertThat(status(transientId)).isEqualTo("FAILED");
        assertThat(errorMessage(transientId)).isEqualTo(
                AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE);
        assertThat(completedAt(transientId))
                .as("already-recovered row must not be re-mutated on the second startup")
                .isEqualTo(transientCompletedAt);
        assertThat(recoveryAuditCount(transientId))
                .as("no duplicate recovery audit on the second startup")
                .isEqualTo(transientAuditAfterFirst);
        assertThat(status(durableId))
                .as("DURABLE command must stay PENDING across a second restart").isEqualTo("PENDING");
        // The DURABLE command is still claimable after two recoveries.
        Map<String, Object> polled = poll(agentId);
        assertThat(polled.get("id")).isEqualTo(durableId);
        assertThat(polled.get("status")).isEqualTo("DISPATCHED");
        assertThat(((Number) polled.get("attempts")).longValue()).isEqualTo(1L);
    }

    // -------------------------------------------------------- helpers

    private String seed() {
        String agentId = "agent-restart-" + UUID.randomUUID();
        String instanceId = "inst-restart-" + UUID.randomUUID();
        harness.seedRuntime(agentId, instanceId);
        return agentId;
    }

    private String createDurable(String agentId, String type, long maxAttempts) {
        return createManual(agentId, type, maxAttempts);
    }

    private String createTransient(String agentId, String type, long maxAttempts) {
        return createManual(agentId, type, maxAttempts);
    }

    private String createManual(String agentId, String type, long maxAttempts) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", type);
        request.put("maxAttempts", maxAttempts);
        RequestContext admin = RestartRecoveryHarness.admin();
        return String.valueOf(harness.commands().createManualCommand(admin, agentId, request).get("id"));
    }

    private Map<String, Object> poll(String agentId) {
        return harness.commands().pollNext(agentId, RestartRecoveryHarness.agentContext(agentId),
                Map.of("leaseSeconds", 60));
    }

    private String status(String id) {
        return harness.jdbc().queryForObject("select status from agent_command where id = ?",
                String.class, id);
    }

    private String errorMessage(String id) {
        return harness.jdbc().queryForObject("select error_message from agent_command where id = ?",
                String.class, id);
    }

    private java.sql.Timestamp completedAt(String id) {
        return harness.jdbc().queryForObject("select completed_at from agent_command where id = ?",
                java.sql.Timestamp.class, id);
    }

    private long recoveryAuditCount(String commandId) {
        Long count = harness.jdbc().queryForObject(
                "select count(*) from audit_record where action = 'agent_command.recover_transient' "
                        + "and resource_id = ?", Long.class, commandId);
        return count == null ? 0L : count;
    }
}
