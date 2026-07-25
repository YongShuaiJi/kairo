package com.example.kairo.platform.command;

import com.example.kairo.platform.fencing.FencingTokenService;
import com.example.kairo.platform.rollout.RuleUnloadService;
import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.kairo.platform.command.ReconciliationTestSupport.chain;
import static com.example.kairo.platform.command.ReconciliationTestSupport.emptySnapshot;
import static com.example.kairo.platform.command.ReconciliationTestSupport.persistSnapshotDirect;
import static com.example.kairo.platform.command.ReconciliationTestSupport.rule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedDesiredRule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedExecution;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedInstance;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedSucceededOperation;
import static com.example.kairo.platform.command.ReconciliationTestSupport.setAgentOffline;
import static com.example.kairo.platform.command.ReconciliationTestSupport.setAgentOnline;
import static com.example.kairo.platform.command.ReconciliationTestSupport.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-E &sect;8.5 item 3: an unload submitted while the agent is unreachable does not fail and
 * does not fabricate UNLOADED. It creates a persistent compensation record (operation UNLOADING +
 * DISPATCHED rollback_execution + OFFLINE_PENDING execution). On reconnect the compensation sweep
 * reads the real actual snapshot, dispatches the precise RESET_CLASS (operation-owned, carrying the
 * rollbackExecutionId), and the agent's ack completes the rollback + operation to UNLOADED and
 * records rule_runtime_status REMOVED &mdash; the rule is actually unloaded, not claimed unloaded.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1e_offlineunload;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class OfflineAgentUnloadCompensationIntegrationTest {

    private static final String TARGET_CLASS = "com.test.OfflineUnloadSvc";
    private static final String TARGET_LOADER = "loader-ou";

    @Autowired RuleUnloadService unloadService;
    @Autowired FencingTokenService fencingTokens;
    @Autowired AgentCommandService commands;
    @Autowired AgentReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;

    private String agentId;
    private String instanceId;
    private String operationId;
    private String processStartId;
    private RequestContext admin;
    private RequestContext agentCtx;

    @BeforeEach
    void setUp() {
        jdbc.update("insert into project(id, organization_id, name, created_at) "
                + "select 'proj-default','org-default','Default Project',current_timestamp "
                + "where not exists (select 1 from project where id='proj-default')");
        jdbc.update("insert into application(id, project_id, name, created_at) "
                + "select 'app-default','proj-default','Default Application',current_timestamp "
                + "where not exists (select 1 from application where id='app-default')");
        jdbc.update("insert into environment(id, application_id, name, type, created_at) "
                + "select 'env-dev','app-default','dev','dev',current_timestamp "
                + "where not exists (select 1 from environment where id='env-dev')");
        String n = UUID.randomUUID().toString();
        agentId = "agent-ou-" + n;
        instanceId = "inst-ou-" + n;
        operationId = "op-ou-" + n;
        processStartId = "ou-host:" + n + ":1700000000000";
        seedInstance(jdbc, agentId, instanceId, processStartId);
        seedDesiredRule(jdbc, "rule-ou", 1, instanceId, TARGET_CLASS, TARGET_LOADER,
                "compute", "(I)I", "METHOD_ENTER",
                "{\"script\":\"return mock.returnValue(7)\",\"phase\":\"BEFORE\"}", "ACTIVE");
        seedSucceededOperation(jdbc, operationId, "rule-ou", 1);
        seedExecution(jdbc, "exec-ou-" + n, operationId, instanceId, 1, "SUCCEEDED");
        admin = new RequestContext("system", "corr-ou", "127.0.0.1", "system", "test");
        agentCtx = new RequestContext(agentId, "corr-ou", "127.0.0.1", "agent", "test");
    }

    @AfterEach
    void tearDown() {
        if (agentId != null) {
            jdbc.update("delete from agent_command where agent_id = ?", agentId);
            jdbc.update("delete from rollback_execution where operation_plan_id = ?", operationId);
            jdbc.update("delete from agent_runtime_state where agent_id = ?", agentId);
            jdbc.update("delete from degraded_class where agent_id = ?", agentId);
            jdbc.update("delete from agent_instance where id = ?", agentId);
        }
        if (operationId != null) {
            jdbc.update("delete from rollout_instance_execution where operation_plan_id = ?", operationId);
            jdbc.update("delete from operation_plan where id = ?", operationId);
        }
        if (instanceId != null) {
            jdbc.update("delete from rule_runtime_status where instance_id = ?", instanceId);
            jdbc.update("delete from instance where id = ?", instanceId);
        }
        jdbc.update("delete from rule_target where rule_version_id like 'rule-ou:%'");
        jdbc.update("delete from rule_version where rule_id = 'rule-ou'");
        jdbc.update("delete from rule where id = 'rule-ou'");
    }

    @Test
    void unloadWhileOfflineCompensatesOnReconnect() {
        // The agent is unreachable when the unload is submitted.
        setAgentOffline(jdbc, agentId);
        submitOfflineUnload();

        assertThat(jdbc.queryForObject(
                "select target_class_name from rollback_execution where operation_plan_id = ?",
                String.class, operationId)).isEqualTo(TARGET_CLASS);
        assertThat(jdbc.queryForObject(
                "select target_class_id from rollback_execution where operation_plan_id = ?",
                String.class, operationId)).isEqualTo(TARGET_CLASS);

        // Persistent compensation record: operation UNLOADING, rollback DISPATCHED, execution
        // OFFLINE_PENDING, no RESET_CLASS dispatched, and the rule still desired ACTIVE.
        assertThat(operationStatus()).isEqualTo("UNLOADING");
        assertThat(rollbackStatus()).isEqualTo("DISPATCHED");
        assertThat(executionStatus()).isEqualTo("OFFLINE_PENDING");
        assertThat(resetClassCommandCount()).isZero();
        assertThat(ruleRuntimeStatus()).isEqualTo("ACTIVE");

        // The agent reconnects: it is reachable again and reports the rule still applied.
        setAgentOnline(jdbc, agentId);
        persistSnapshotDirect(jdbc, agentId, instanceId, processStartId,
                snapshot(agentId, processStartId,
                        chain("com.test.OfflineUnloadSvc#compute#METHOD_ENTER", TARGET_CLASS, TARGET_LOADER,
                                "compute", "(I)I", "METHOD_ENTER", "ACTIVE",
                                List.of("rule-ou"), null),
                        List.of("rule-ou"),
                        List.of(rule("rule-ou", 1))));

        // Compensation reads the actual snapshot and dispatches the precise RESET_CLASS.
        reconciliation.reconcileAgent(admin, agentId);

        assertThat(resetClassCommandCount()).isEqualTo(1);
        assertThat(executionStatus()).isEqualTo("UNLOADING");
        Map<String, Object> command = resetClassCommand();
        assertThat(command.get("rollback_execution_id")).asString().isNotBlank();
        assertThat(command.get("idempotency_key")).asString()
                .isEqualTo("unload:" + operationId + ":" + agentId);

        // The agent acks the real unload.
        Map<String, Object> polled = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        assertThat(polled.get("status")).isEqualTo("DISPATCHED");
        long attempts = ((Number) polled.get("attempts")).longValue();
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", "ACKED");
        ack.put("expectedAttempts", attempts);
        ack.put("reason", "real unload completed");
        ack.put("result", Map.of("removedRuleIds", List.of("rule-ou:1")));
        commands.ack(String.valueOf(polled.get("id")), agentCtx, ack);

        // The unload is genuinely complete: operation UNLOADED, rollback SUCCEEDED, execution
        // UNLOADED and rule_runtime_status REMOVED.
        assertThat(operationStatus()).isEqualTo("UNLOADED");
        assertThat(rollbackStatus()).isEqualTo("SUCCEEDED");
        assertThat(executionStatus()).isEqualTo("UNLOADED");
        assertThat(ruleRuntimeStatus()).isEqualTo("REMOVED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void truncatedActualCannotConfirmPendingUnloadAsGone() {
        setAgentOffline(jdbc, agentId);
        submitOfflineUnload();
        setAgentOnline(jdbc, agentId);

        Map<String, Object> truncated = emptySnapshot(agentId, processStartId);
        Map<String, Object> truncation = (Map<String, Object>) truncated.get("truncation");
        Map<String, Object> chains = (Map<String, Object>) truncation.get("chains");
        chains.put("total", 1);
        chains.put("included", 0);
        chains.put("reason", "ENTRY_LIMIT");
        persistSnapshotDirect(jdbc, agentId, instanceId, processStartId, truncated);

        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(admin, agentId);

        assertThat(result.notes()).containsExactly(
                "INCOMPLETE_ACTUAL_SNAPSHOT: rules or chains were truncated; reconciliation skipped");
        assertThat(executionStatus()).isEqualTo("OFFLINE_PENDING");
        assertThat(operationStatus()).isEqualTo("UNLOADING");
        assertThat(ruleRuntimeStatus()).isEqualTo("ACTIVE");
        assertThat(resetClassCommandCount()).isZero();
    }

    private void submitOfflineUnload() {
        String token = String.valueOf(fencingTokens.issue(admin, "operation_plan", operationId,
                "offline unload", 0).get("token"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("expectedStatus", "SUCCEEDED");
        request.put("expectedVersion", 2L);
        request.put("reason", "offline unload");
        request.put("fencingToken", token);
        unloadService.unload(operationId, admin, request);
    }

    private String operationStatus() {
        return jdbc.queryForObject("select status from operation_plan where id = ?", String.class, operationId);
    }

    private String rollbackStatus() {
        return jdbc.queryForObject("select status from rollback_execution where operation_plan_id = ?",
                String.class, operationId);
    }

    private String executionStatus() {
        return jdbc.queryForObject(
                "select status from rollout_instance_execution where operation_plan_id = ?",
                String.class, operationId);
    }

    private String ruleRuntimeStatus() {
        return jdbc.queryForObject(
                "select status from rule_runtime_status where rule_id = ? and instance_id = ?",
                String.class, "rule-ou", instanceId);
    }

    private long resetClassCommandCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from agent_command where agent_id = ? and command_type = 'RESET_CLASS'",
                Long.class, agentId);
        return count == null ? 0L : count;
    }

    private Map<String, Object> resetClassCommand() {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type = 'RESET_CLASS'",
                agentId).get(0);
    }
}
