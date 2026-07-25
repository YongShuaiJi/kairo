package com.example.kairo.platform.command;

import com.example.kairo.platform.fencing.FencingTokenService;
import com.example.kairo.platform.rollout.RuleUnloadService;
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
 * V1.7 M1-E &sect;8.5 item 6: retrying the compensation (or re-running reconciliation) reuses the
 * original target snapshot and the stable idempotency key &mdash; it never re-dispatches a command
 * already in flight and never re-expands the target set.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1e_retryidem;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class UnloadRetryIdempotencyIntegrationTest {

    private static final String TARGET_CLASS = "com.test.RetryIdemSvc";
    private static final String TARGET_LOADER = "loader-ri";

    @Autowired RuleUnloadService unloadService;
    @Autowired FencingTokenService fencingTokens;
    @Autowired AgentReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;

    private String agentId;
    private String instanceId;
    private String operationId;
    private String processStartId;
    private RequestContext admin;

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
        agentId = "agent-ri-" + n;
        instanceId = "inst-ri-" + n;
        operationId = "op-ri-" + n;
        processStartId = "ri-host:" + n + ":1700000000000";
        seedInstance(jdbc, agentId, instanceId, processStartId);
        seedDesiredRule(jdbc, "rule-ri", 1, instanceId, TARGET_CLASS, TARGET_LOADER,
                "compute", "(I)I", "METHOD_ENTER",
                "{\"script\":\"return mock.returnValue(7)\",\"phase\":\"BEFORE\"}", "ACTIVE");
        seedSucceededOperation(jdbc, operationId, "rule-ri", 1);
        seedExecution(jdbc, "exec-ri-" + n, operationId, instanceId, 1, "SUCCEEDED");
        admin = new RequestContext("system", "corr-ri", "127.0.0.1", "system", "test");
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
        jdbc.update("delete from rule_target where rule_version_id like 'rule-ri:%'");
        jdbc.update("delete from rule_version where rule_id = 'rule-ri'");
        jdbc.update("delete from rule where id = 'rule-ri'");
    }

    @Test
    void retryReusesOriginalTargetAndStableIdempotencyKey() {
        setAgentOffline(jdbc, agentId);
        String token = String.valueOf(fencingTokens.issue(admin, "operation_plan", operationId,
                "retry unload", 0).get("token"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("expectedStatus", "SUCCEEDED");
        request.put("expectedVersion", 2L);
        request.put("reason", "retry unload");
        request.put("fencingToken", token);
        unloadService.unload(operationId, admin, request);
        assertThat(executionStatus()).isEqualTo("OFFLINE_PENDING");
        assertThat(rollbackTargetClass()).isEqualTo(TARGET_CLASS);

        // Reconnect + first compensation: dispatches the precise RESET_CLASS using the original
        // target snapshot, even if the live rule_target row has since been deleted and another
        // unrelated actual chain sorts first.
        setAgentOnline(jdbc, agentId);
        jdbc.update("delete from rule_target where rule_version_id = 'rule-ri:1'");
        persistSnapshotDirect(jdbc, agentId, instanceId, processStartId,
                snapshot(agentId, processStartId, List.of(
                        chain("com.aaa.Other#other#METHOD_ENTER", "com.aaa.Other", "loader-other",
                                "other", "()V", "METHOD_ENTER", "ACTIVE",
                                List.of("rule-other"), null),
                        chain("com.test.RetryIdemSvc#compute#METHOD_ENTER", TARGET_CLASS, TARGET_LOADER,
                                "compute", "(I)I", "METHOD_ENTER", "ACTIVE",
                                List.of("rule-ri"), null)),
                        List.of(rule("rule-other", 1), rule("rule-ri", 1))));
        reconciliation.reconcileAgent(admin, agentId);
        assertThat(resetClassCount()).isEqualTo(1);
        Map<String, Object> first = resetClassCommand();
        String idempotencyKey = String.valueOf(first.get("idempotency_key"));
        assertThat(idempotencyKey).isEqualTo("unload:" + operationId + ":" + agentId);
        assertThat(payloadClassName(first)).isEqualTo(TARGET_CLASS);

        // Retry: re-running the compensation must NOT re-dispatch (the in-flight command is reused)
        // and must NOT re-expand the target set (no extra commands, no re-captured targets).
        reconciliation.reconcileAgent(admin, agentId);
        reconciliation.reconcileAgent(admin, agentId);
        assertThat(resetClassCount()).isEqualTo(1);
        assertThat(resetClassCommand().get("idempotency_key")).isEqualTo(idempotencyKey);
        assertThat(payloadClassName(resetClassCommand())).isEqualTo(TARGET_CLASS);
        assertThat(targetSnapshotCount()).isZero();
        assertThat(executionStatus()).isEqualTo("UNLOADING");
    }

    private String payloadClassName(Map<String, Object> command) {
        return String.valueOf(com.example.kairo.platform.service.PlatformJson
                .readMap(String.valueOf(command.get("payload_json"))).get("className"));
    }

    private String executionStatus() {
        return jdbc.queryForObject(
                "select status from rollout_instance_execution where operation_plan_id = ?",
                String.class, operationId);
    }

    private long resetClassCount() {
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

    private long targetSnapshotCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from rollout_target_snapshot where operation_plan_id = ?",
                Long.class, operationId);
        return count == null ? 0L : count;
    }

    private String rollbackTargetClass() {
        return jdbc.queryForObject(
                "select target_class_name from rollback_execution where operation_plan_id = ?",
                String.class, operationId);
    }
}
