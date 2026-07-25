package com.example.kairo.platform.command;

import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.kairo.platform.command.ReconciliationTestSupport.emptySnapshot;
import static com.example.kairo.platform.command.ReconciliationTestSupport.persistSnapshotDirect;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedDesiredRule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedInstance;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-F &sect;8.6 item 4: after a local emergency op (disable-all / reset-all / reset-class via the
 * loopback api) performed while the Platform was unavailable, the refreshed actual snapshot carries
 * {@code emergency=true}. Reconciliation must not blindly re-apply desired state that would undo the
 * operator's manual recovery: it surfaces an {@code emergency_hold} and enqueues no convergence
 * command. Once the operator resumes with {@code enable-all} (clearing the flag), reconciliation
 * proceeds normally.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1f_emergency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "kairo.platform.reconciliation.scheduler.enabled=false",
        "kairo.platform.reconciliation.snapshot-request-delay-ms=0"
})
@ActiveProfiles("test")
class PostEmergencyReconciliationIntegrationTest {

    private static final String PROCESS_START_ID = "emergency-host:5:1700000000000";
    private static final String TARGET_CLASS = "com.test.Svc";
    private static final String TARGET_LOADER = "loader-1";

    @Autowired AgentReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;

    private String agentId;
    private String instanceId;
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
        agentId = "agent-emergency-" + UUID.randomUUID();
        instanceId = "inst-emergency-" + UUID.randomUUID();
        seedInstance(jdbc, agentId, instanceId, PROCESS_START_ID);
        systemContext = new RequestContext("system", "corr-emergency", "127.0.0.1", "system", "test");
        seedDesiredRule(jdbc, "rule-em", 1, instanceId, TARGET_CLASS, TARGET_LOADER,
                "compute", "(I)I", "METHOD_ENTER",
                "{\"script\":\"return mock.returnValue(7)\",\"phase\":\"BEFORE\"}", "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        if (agentId != null) {
            jdbc.update("delete from degraded_class where agent_id = ?", agentId);
            jdbc.update("delete from agent_command where agent_id = ?", agentId);
            jdbc.update("delete from agent_runtime_state where agent_id = ?", agentId);
            jdbc.update("delete from agent_instance where id = ?", agentId);
        }
        if (instanceId != null) {
            jdbc.update("delete from rule_runtime_status where instance_id = ?", instanceId);
            jdbc.update("delete from instance where id = ?", instanceId);
        }
        jdbc.update("delete from rule_target where rule_version_id like 'rule-em:%'");
        jdbc.update("delete from rule_version where rule_id = 'rule-em'");
        jdbc.update("delete from rule where id = 'rule-em'");
    }

    @Test
    void emergencySnapshotDefersReconciliationWithoutUndoingManualRecovery() {
        // The operator ran an emergency reset-all (loopback) while the Platform was down: the actual
        // snapshot reflects the result (chains gone) and carries emergency=true.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                emergencySnapshot(true));

        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(systemContext, agentId);

        // Item 4: reconciliation does not blindly re-apply. No APPLY_RULE/RESET_CLASS is enqueued.
        assertThat(result.applied()).isZero();
        assertThat(result.reset()).isZero();
        assertThat(result.notes()).contains("emergency_hold");
        assertThat(convergenceCommands())
                .as("no convergence command enqueued while emergency hold is active")
                .isEmpty();
        assertThat(refreshCommands())
                .as("a fresh emergency snapshot must not trigger an immediate refresh loop")
                .isEmpty();

        jdbc.update("update agent_runtime_state set received_at = ? where agent_id = ?",
                Timestamp.from(Instant.now().minusSeconds(31)), agentId);
        reconciliation.reconcileAgent(systemContext, agentId);
        assertThat(refreshCommands())
                .as("a held agent is sampled at the bounded refresh interval so resume is observed")
                .hasSize(1);
    }

    @Test
    void clearingEmergencyResumesReconciliation() {
        // Emergency hold active: deferred.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                emergencySnapshot(true));
        reconciliation.reconcileAgent(systemContext, agentId);
        assertThat(convergenceCommands()).isEmpty();

        // The operator resumes with `enable-all` (loopback): the agent clears the emergency flag.
        // The chains are still gone, so a fresh snapshot reports emergency=false + empty actual.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                emergencySnapshot(false));

        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(systemContext, agentId);

        // Reconciliation resumes: desired ACTIVE + actual missing -> APPLY_RULE re-applied.
        assertThat(result.applied()).isEqualTo(1);
        assertThat(convergenceCommands()).hasSize(1);
    }

    /** An empty actual snapshot (no chains) carrying the emergency flag, as a reset-all would. */
    private Map<String, Object> emergencySnapshot(boolean emergency) {
        Map<String, Object> snapshot = emptySnapshot(agentId, PROCESS_START_ID);
        snapshot.put("emergency", emergency);
        return snapshot;
    }

    private List<Map<String, Object>> convergenceCommands() {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type in ('APPLY_RULE','RESET_CLASS','RESET_ALL')",
                agentId);
    }

    private List<Map<String, Object>> refreshCommands() {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type = 'REFRESH_RUNTIME_STATE'",
                agentId);
    }
}
