package com.example.kairo.platform.command;

import com.example.kairo.platform.service.PlatformMaintenanceService;
import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static com.example.kairo.platform.command.ReconciliationTestSupport.seedDesiredRule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedExecution;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedInstance;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedSucceededOperation;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-E &sect;8.5 items 1, 2: an agent going offline is not an unload. The lease-expiry sweep
 * must not fabricate {@code operation_plan} UNLOADED or {@code rule_runtime_status} REMOVED the
 * moment an agent becomes unreachable &mdash; the rule may still be applied in a JVM that is merely
 * disconnected. The authoritative desired state stays ACTIVE and the operation keeps its real
 * SUCCEEDED status; reconnect convergence (re-apply on a new empty JVM, or a precise unload) is
 * driven later by the real actual snapshot.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1e_agentgone;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class AgentGoneIsNotUnloadedIntegrationTest {

    private static final String TARGET_CLASS = "com.test.AgentGoneSvc";
    private static final String TARGET_LOADER = "loader-gone";

    @Autowired PlatformMaintenanceService maintenance;
    @Autowired JdbcTemplate jdbc;

    private String agentId;
    private String instanceId;
    private String operationId;
    private RequestContext systemContext;

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
        agentId = "agent-gone-" + n;
        instanceId = "inst-gone-" + n;
        operationId = "op-gone-" + n;
        String processStartId = "gone-host:" + n + ":1700000000000";
        seedInstance(jdbc, agentId, instanceId, processStartId);
        seedDesiredRule(jdbc, "rule-gone", 1, instanceId, TARGET_CLASS, TARGET_LOADER,
                "compute", "(I)I", "METHOD_ENTER",
                "{\"script\":\"return mock.returnValue(7)\",\"phase\":\"BEFORE\"}", "ACTIVE");
        seedSucceededOperation(jdbc, operationId, "rule-gone", 1);
        seedExecution(jdbc, "exec-gone-" + n, operationId, instanceId, 1, "SUCCEEDED");
        systemContext = new RequestContext("system", "corr-gone", "127.0.0.1", "system", "test");
    }

    @AfterEach
    void tearDown() {
        if (agentId != null) {
            jdbc.update("delete from agent_command where agent_id = ?", agentId);
            jdbc.update("delete from rollback_execution where operation_plan_id = ?", operationId);
            jdbc.update("delete from agent_runtime_state where agent_id = ?", agentId);
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
        jdbc.update("delete from rule_target where rule_version_id like 'rule-gone:%'");
        jdbc.update("delete from rule_version where rule_id = 'rule-gone'");
        jdbc.update("delete from rule where id = 'rule-gone'");
    }

    @Test
    void agentGoneDoesNotFabricateUnloadedOrRemoved() {
        // The agent's lease expires (it goes offline) but the JVM may still carry the rule.
        jdbc.update("update agent_instance set lease_expires_at = timestamp '2020-01-01 00:00:00' "
                + "where id = ?", agentId);

        maintenance.expireRuntimeLeases();

        // No fabricated unload: the operation keeps its real SUCCEEDED status, the rule stays
        // ACTIVE (desired), and no AGENT_GONE terminal_source is written.
        assertThat(operationStatus()).isEqualTo("SUCCEEDED");
        assertThat(terminalSource()).isEqualTo("");
        assertThat(ruleRuntimeStatus()).isEqualTo("ACTIVE");
        assertThat(rollbackCount()).isZero();
    }

    private String operationStatus() {
        return jdbc.queryForObject("select status from operation_plan where id = ?", String.class, operationId);
    }

    private String terminalSource() {
        return jdbc.queryForObject("select terminal_source from operation_plan where id = ?",
                String.class, operationId);
    }

    private String ruleRuntimeStatus() {
        return jdbc.queryForObject(
                "select status from rule_runtime_status where rule_id = ? and instance_id = ?",
                String.class, "rule-gone", instanceId);
    }

    private long rollbackCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from rollback_execution where operation_plan_id = ?", Long.class, operationId);
        return count == null ? 0L : count;
    }
}
