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

import static com.example.kairo.platform.command.ReconciliationTestSupport.persistSnapshotDirect;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedDesiredRule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedExecution;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedInstance;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedRuleRuntimeStatus;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedSucceededOperation;
import static com.example.kairo.platform.command.ReconciliationTestSupport.setAgentOffline;
import static com.example.kairo.platform.command.ReconciliationTestSupport.setAgentOnline;
import static com.example.kairo.platform.command.ReconciliationTestSupport.snapshot;
import static com.example.kairo.platform.command.ReconciliationTestSupport.chain;
import static com.example.kairo.platform.command.ReconciliationTestSupport.rule;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-E &sect;8.5 item 5: a multi-target unload records each instance's own outcome
 * (UNLOADING / UNLOADED / OFFLINE_PENDING) and the aggregate operation status never overrides a
 * per-instance fact. Two reachable instances unload on ack; the third (its agent offline) stays
 * OFFLINE_PENDING and keeps the operation UNLOADING until the agent reconnects and the compensation
 * sweep completes it from the actual snapshot.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1e_multitarget;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class MultiTargetPartialFailureIntegrationTest {

    private static final String TARGET_CLASS = "com.test.MultiTargetSvc";
    private static final String TARGET_LOADER = "loader-mt";

    @Autowired RuleUnloadService unloadService;
    @Autowired FencingTokenService fencingTokens;
    @Autowired AgentCommandService commands;
    @Autowired AgentReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;

    private String[] agents = new String[3];
    private String[] instances = new String[3];
    private String[] processStartIds = new String[3];
    private String operationId;
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
        operationId = "op-mt-" + n;
        for (int i = 0; i < 3; i++) {
            agents[i] = "agent-mt-" + n + "-" + i;
            instances[i] = "inst-mt-" + n + "-" + i;
            processStartIds[i] = "mt-host-" + i + ":" + n + ":1700000000000";
            seedInstance(jdbc, agents[i], instances[i], processStartIds[i]);
        }
        // The rule is applied to all three instances (rule + version + target seeded once; one
        // runtime-status row per instance).
        seedDesiredRule(jdbc, "rule-mt", 1, instances[0], TARGET_CLASS, TARGET_LOADER,
                "compute", "(I)I", "METHOD_ENTER",
                "{\"script\":\"return mock.returnValue(7)\",\"phase\":\"BEFORE\"}", "ACTIVE");
        seedRuleRuntimeStatus(jdbc, "rule-mt", 1, instances[1], "ACTIVE");
        seedRuleRuntimeStatus(jdbc, "rule-mt", 1, instances[2], "ACTIVE");
        seedSucceededOperation(jdbc, operationId, "rule-mt", 1);
        for (int i = 0; i < 3; i++) {
            seedExecution(jdbc, "exec-mt-" + n + "-" + i, operationId, instances[i], 1, "SUCCEEDED");
        }
        admin = new RequestContext("system", "corr-mt", "127.0.0.1", "system", "test");
    }

    @AfterEach
    void tearDown() {
        for (int i = 0; i < 3; i++) {
            if (agents[i] != null) {
                jdbc.update("delete from agent_command where agent_id = ?", agents[i]);
                jdbc.update("delete from agent_runtime_state where agent_id = ?", agents[i]);
                jdbc.update("delete from agent_instance where id = ?", agents[i]);
            }
        }
        if (operationId != null) {
            jdbc.update("delete from rollback_execution where operation_plan_id = ?", operationId);
            jdbc.update("delete from rollout_instance_execution where operation_plan_id = ?", operationId);
            jdbc.update("delete from operation_plan where id = ?", operationId);
        }
        // Instances can be deleted only after their rollout_instance_execution rows are gone (FK).
        for (int i = 0; i < 3; i++) {
            if (instances[i] != null) {
                jdbc.update("delete from rule_runtime_status where instance_id = ?", instances[i]);
                jdbc.update("delete from instance where id = ?", instances[i]);
            }
        }
        jdbc.update("delete from rule_target where rule_version_id like 'rule-mt:%'");
        jdbc.update("delete from rule_version where rule_id = 'rule-mt'");
        jdbc.update("delete from rule where id = 'rule-mt'");
    }

    @Test
    void perInstanceOutcomesAreRecordedAndAggregateDoesNotOverride() {
        // Instances 0 and 1 are reachable; instance 2's agent is offline.
        setAgentOffline(jdbc, agents[2]);
        String token = String.valueOf(fencingTokens.issue(admin, "operation_plan", operationId,
                "multi-target unload", 0).get("token"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("expectedStatus", "SUCCEEDED");
        request.put("expectedVersion", 2L);
        request.put("reason", "multi-target unload");
        request.put("fencingToken", token);
        unloadService.unload(operationId, admin, request);

        // Per-instance facts: the two reachable instances are UNLOADING (RESET_CLASS dispatched),
        // the offline one is OFFLINE_PENDING. The operation stays UNLOADING (not all terminal).
        assertThat(executionStatus(instances[0])).isEqualTo("UNLOADING");
        assertThat(executionStatus(instances[1])).isEqualTo("UNLOADING");
        assertThat(executionStatus(instances[2])).isEqualTo("OFFLINE_PENDING");
        assertThat(resetClassCount()).isEqualTo(2);
        assertThat(operationStatus()).isEqualTo("UNLOADING");

        // The two reachable agents ack the real unload: their instances become UNLOADED.
        ackResetClass(agents[0], instances[0], true);
        ackResetClass(agents[1], instances[1], true);
        assertThat(executionStatus(instances[0])).isEqualTo("UNLOADED");
        assertThat(executionStatus(instances[1])).isEqualTo("UNLOADED");
        assertThat(ruleRuntimeStatus(instances[0])).isEqualTo("REMOVED");
        assertThat(ruleRuntimeStatus(instances[1])).isEqualTo("REMOVED");
        // The offline instance keeps its fact and holds the operation UNLOADING.
        assertThat(executionStatus(instances[2])).isEqualTo("OFFLINE_PENDING");
        assertThat(ruleRuntimeStatus(instances[2])).isEqualTo("ACTIVE");
        assertThat(operationStatus()).isEqualTo("UNLOADING");

        // Instance 2 reconnects and the compensation sweep completes it from the actual snapshot.
        setAgentOnline(jdbc, agents[2]);
        persistSnapshotDirect(jdbc, agents[2], instances[2], processStartIds[2],
                snapshot(agents[2], processStartIds[2],
                        chain("com.test.MultiTargetSvc#compute#METHOD_ENTER", TARGET_CLASS, TARGET_LOADER,
                                "compute", "(I)I", "METHOD_ENTER", "ACTIVE",
                                List.of("rule-mt"), null),
                        List.of("rule-mt"),
                        List.of(rule("rule-mt", 1))));
        reconciliation.reconcileAgent(admin, agents[2]);
        ackResetClass(agents[2], instances[2], true);

        // Now every instance is UNLOADED and the aggregate operation can complete to UNLOADED.
        assertThat(executionStatus(instances[2])).isEqualTo("UNLOADED");
        assertThat(ruleRuntimeStatus(instances[2])).isEqualTo("REMOVED");
        assertThat(operationStatus()).isEqualTo("UNLOADED");
    }

    @Test
    void degradedResetIsRecordedAsPerInstanceFailureAndAggregateFailed() {
        String token = String.valueOf(fencingTokens.issue(admin, "operation_plan", operationId,
                "partial failure unload", 0).get("token"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("expectedStatus", "SUCCEEDED");
        request.put("expectedVersion", 2L);
        request.put("reason", "partial failure unload");
        request.put("fencingToken", token);
        unloadService.unload(operationId, admin, request);

        ackResetClass(agents[0], instances[0], true);
        ackResetClass(agents[1], instances[1], false);
        ackResetClass(agents[2], instances[2], true);

        assertThat(executionStatus(instances[0])).isEqualTo("UNLOADED");
        assertThat(executionStatus(instances[1])).isEqualTo("FAILED");
        assertThat(executionStatus(instances[2])).isEqualTo("UNLOADED");
        assertThat(ruleRuntimeStatus(instances[0])).isEqualTo("REMOVED");
        assertThat(ruleRuntimeStatus(instances[1])).isEqualTo("ACTIVE");
        assertThat(ruleRuntimeStatus(instances[2])).isEqualTo("REMOVED");
        assertThat(operationStatus()).isEqualTo("FAILED");
        assertThat(rollbackStatus()).isEqualTo("FAILED");
    }

    private void ackResetClass(String agentId, String instanceId, boolean succeeded) {
        RequestContext agentCtx = new RequestContext(agentId, "corr-mt", "127.0.0.1", "agent", "test");
        Map<String, Object> polled = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        assertThat(polled.get("status")).isEqualTo("DISPATCHED");
        assertThat(polled.get("command_type")).isEqualTo("RESET_CLASS");
        long attempts = ((Number) polled.get("attempts")).longValue();
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", "ACKED");
        ack.put("expectedAttempts", attempts);
        ack.put("reason", "unload " + instanceId);
        ack.put("result", succeeded
                ? Map.of("removedRuleIds", List.of("rule-mt:1"),
                        "failedRules", Map.of(), "degraded", false)
                : Map.of("removedRuleIds", List.of(),
                        "failedRules", Map.of("rule-mt:1", "retransform failed"),
                        "degraded", true));
        commands.ack(String.valueOf(polled.get("id")), agentCtx, ack);
    }

    private String operationStatus() {
        return jdbc.queryForObject("select status from operation_plan where id = ?", String.class, operationId);
    }

    private String rollbackStatus() {
        return jdbc.queryForObject(
                "select status from rollback_execution where operation_plan_id = ?",
                String.class, operationId);
    }

    private String executionStatus(String instanceId) {
        return jdbc.queryForObject(
                "select status from rollout_instance_execution where operation_plan_id = ? and instance_id = ?",
                String.class, operationId, instanceId);
    }

    private String ruleRuntimeStatus(String instanceId) {
        return jdbc.queryForObject(
                "select status from rule_runtime_status where rule_id = ? and instance_id = ?",
                String.class, "rule-mt", instanceId);
    }

    private long resetClassCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from agent_command where command_type = 'RESET_CLASS' "
                        + "and idempotency_key like 'unload:" + operationId + ":%'",
                Long.class);
        return count == null ? 0L : count;
    }
}
