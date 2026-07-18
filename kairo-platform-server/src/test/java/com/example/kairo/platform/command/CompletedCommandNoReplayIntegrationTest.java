package com.example.kairo.platform.command;

import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-B &sect;8.2 (terminal immutability): ACKED and FAILED commands remain terminal across a
 * Platform restart - they are never returned to the Agent (the poll skips them) and their
 * rollout/unload/audit side effects are never re-run.
 *
 * <p>Each test launches real {@link com.example.kairo.platform.KairoPlatformApplication} contexts
 * against a per-method file-backed H2. No sleeps.
 */
class CompletedCommandNoReplayIntegrationTest {

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
    void ackedCommandIsNotReturnedToAgentAfterRestart() {
        harness.start();
        Runtime rt = seed();
        String id = createCommand(rt.agentId(), "STOP_AGENT", 5);
        poll(rt.agentId()); // -> DISPATCHED attempts=1
        // STOP_AGENT ACKED has a real side effect (agent -> DISABLED); the no-replay assertion
        // checks it is not re-toggled on restart.
        ack(id, rt.agentId(), "ACKED", 1, Map.of("stopped", true), null);
        assertThat(status(id)).isEqualTo("ACKED");
        assertThat(agentStatus(rt.agentId())).isEqualTo("DISABLED");
        long ackAudits = ackAuditCount(id);
        assertThat(ackAudits).isEqualTo(1L);
        harness.stop();

        // §8.2#4: a terminal ACKED command is never returned to the Agent and is never re-acked.
        harness.start();
        Map<String, Object> polled = poll(rt.agentId());
        assertThat(polled.get("status")).isEqualTo("NO_COMMAND");
        assertThat(status(id)).isEqualTo("ACKED");
        // No re-toggle of the side effect, no duplicate ack audit.
        assertThat(agentStatus(rt.agentId())).isEqualTo("DISABLED");
        assertThat(ackAuditCount(id)).isEqualTo(ackAudits);
    }

    @Test
    void failedCommandIsNotReturnedToAgentAfterRestart() {
        harness.start();
        Runtime rt = seed();
        String id = createCommand(rt.agentId(), "STOP_AGENT", 5);
        poll(rt.agentId());
        ack(id, rt.agentId(), "FAILED", 1, Map.of(), "boom");
        assertThat(status(id)).isEqualTo("FAILED");
        // STOP_AGENT FAILED -> agent set ACTIVE.
        assertThat(agentStatus(rt.agentId())).isEqualTo("ACTIVE");
        long ackAudits = ackAuditCount(id);
        assertThat(ackAudits).isEqualTo(1L);
        long errorBefore = errorMessage(id) == null ? 0 : 1;
        assertThat(errorBefore).isEqualTo(1);
        harness.stop();

        // §8.2#4: a terminal FAILED command is never returned and never re-acked.
        harness.start();
        assertThat(poll(rt.agentId()).get("status")).isEqualTo("NO_COMMAND");
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(errorMessage(id)).isEqualTo("boom");
        assertThat(agentStatus(rt.agentId())).isEqualTo("ACTIVE");
        assertThat(ackAuditCount(id)).isEqualTo(ackAudits);
    }

    @Test
    void ackedCommandLinkedToRolloutIsNotReAdvancedAfterRestart() {
        harness.start();
        Runtime rt = seed();
        // A minimal rule so the ACK's rule_runtime_status insert satisfies the (H2-resident)
        // rule_runtime_status.rule_id FK; V19's FK drop is a no-op under H2 naming.
        harness.jdbc().update("""
                insert into rule(id, application_id, environment_id, name, status,
                  current_draft_version, latest_version, created_by, created_at, updated_by, updated_at)
                values ('rule-test-1', 'app-default', 'env-dev', 'test', 'ENABLED',
                  null, 1, 'system', current_timestamp, 'system', current_timestamp)
                """);
        String operationPlanId = "op-" + UUID.randomUUID();
        // A RUNNING operation plan with automaticUnload disabled so an ACKED execution completes
        // the operation to SUCCEEDED directly (no auto-unload / RESET_CLASS dispatch).
        harness.jdbc().update("""
                insert into operation_plan(id, application_id, environment_id, plan_type, resource_type,
                  resource_id, resource_version, status, version, strategy_json, created_by, created_at,
                  updated_by, updated_at)
                values (?, 'app-default', 'env-dev', 'RULE_ROLLOUT', 'rule', 'rule-test-1', 1, 'RUNNING', 1,
                  '{"automaticUnload":false}', 'system', current_timestamp, 'system', current_timestamp)
                """, operationPlanId);

        String commandId = createCommand(rt.agentId(), "APPLY_RULE", 5);
        poll(rt.agentId()); // -> DISPATCHED attempts=1

        // Link a WAITING_AGENT execution to the command so the ACK advances rollout/operation.
        String executionId = "rie-" + UUID.randomUUID();
        harness.fixtures().insertWaitingRolloutExecution(executionId, operationPlanId,
                rt.instanceId(), commandId);

        // ACK the command -> execution SUCCEEDED (v1->v2), operation SUCCEEDED (v1->v2),
        // and one rule_runtime_status row inserted.
        ack(commandId, rt.agentId(), "ACKED", 1, Map.of("status", "APPLIED"), null);
        assertThat(status(commandId)).isEqualTo("ACKED");
        long executionVersion = executionVersion(executionId);
        long operationVersion = operationVersion(operationPlanId);
        assertThat(executionVersion).isEqualTo(2L);
        assertThat(operationVersion).isEqualTo(2L);
        long runtimeStatusCount = ruleRuntimeStatusCount("rule-test-1");
        assertThat(runtimeStatusCount).isEqualTo(1L);
        long ackAudits = ackAuditCount(commandId);
        long rolloutAudits = rolloutAuditCount(operationPlanId);
        assertThat(ackAudits).isEqualTo(1L);
        assertThat(rolloutAudits).isGreaterThanOrEqualTo(1L);
        harness.stop();

        // §8.2#4: after restart the terminal command is not returned, so rollout/unload cannot be
        // re-advanced -- execution/operation/runtime-status are unchanged and no audit is duplicated.
        harness.start();
        Map<String, Object> polled = poll(rt.agentId());
        assertThat(polled.get("status")).isEqualTo("NO_COMMAND");
        assertThat(status(commandId)).isEqualTo("ACKED");
        assertThat(executionVersion(executionId))
                .as("rollout execution must not be re-advanced").isEqualTo(executionVersion);
        assertThat(operationVersion(operationPlanId))
                .as("operation plan must not be re-advanced").isEqualTo(operationVersion);
        assertThat(ruleRuntimeStatusCount("rule-test-1"))
                .as("rule_runtime_status must not be re-inserted").isEqualTo(runtimeStatusCount);
        assertThat(ackAuditCount(commandId))
                .as("no duplicate ack audit").isEqualTo(ackAudits);
        assertThat(rolloutAuditCount(operationPlanId))
                .as("no duplicate rollout audit").isEqualTo(rolloutAudits);
    }

    // -------------------------------------------------------- helpers

    private Runtime seed() {
        String agentId = "agent-replay-" + UUID.randomUUID();
        String instanceId = "inst-replay-" + UUID.randomUUID();
        harness.seedRuntime(agentId, instanceId);
        return new Runtime(agentId, instanceId);
    }

    private record Runtime(String agentId, String instanceId) {
    }

    private String createCommand(String agentId, String type, long maxAttempts) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", type);
        request.put("maxAttempts", maxAttempts);
        return String.valueOf(harness.commands()
                .createManualCommand(RestartRecoveryHarness.admin(), agentId, request).get("id"));
    }

    private Map<String, Object> poll(String agentId) {
        return harness.commands().pollNext(agentId, RestartRecoveryHarness.agentContext(agentId),
                Map.of("leaseSeconds", 60));
    }

    private void ack(String commandId, String agentId, String resultStatus, long expectedAttempts,
                     Map<String, Object> result, String errorMessage) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", resultStatus);
        request.put("expectedAttempts", expectedAttempts);
        request.put("result", result);
        if (errorMessage != null) {
            request.put("errorMessage", errorMessage);
        }
        harness.commands().ack(commandId, RestartRecoveryHarness.agentContext(agentId), request);
    }

    private String status(String id) {
        return harness.jdbc().queryForObject("select status from agent_command where id = ?",
                String.class, id);
    }

    private String errorMessage(String id) {
        return harness.jdbc().queryForObject("select error_message from agent_command where id = ?",
                String.class, id);
    }

    private String agentStatus(String agentId) {
        return harness.jdbc().queryForObject("select status from agent_instance where id = ?",
                String.class, agentId);
    }

    private long executionVersion(String executionId) {
        return harness.jdbc().queryForObject(
                "select version from rollout_instance_execution where id = ?",
                Long.class, executionId);
    }

    private long operationVersion(String operationPlanId) {
        return harness.jdbc().queryForObject("select version from operation_plan where id = ?",
                Long.class, operationPlanId);
    }

    private long ackAuditCount(String commandId) {
        return harness.jdbc().queryForObject(
                "select count(*) from audit_record where action = 'agent_command.ack' "
                        + "and resource_id = ?", Long.class, commandId);
    }

    private long ruleRuntimeStatusCount(String ruleId) {
        return harness.jdbc().queryForObject(
                "select count(*) from rule_runtime_status where rule_id = ?", Long.class, ruleId);
    }

    private long rolloutAuditCount(String operationPlanId) {
        return harness.jdbc().queryForObject(
                "select count(*) from audit_record where resource_type = 'operation_plan' "
                        + "and resource_id = ?", Long.class, operationPlanId);
    }
}
