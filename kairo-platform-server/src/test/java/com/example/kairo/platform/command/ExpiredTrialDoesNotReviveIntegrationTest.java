package com.example.kairo.platform.command;

import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.kairo.platform.command.ReconciliationTestSupport.emptySnapshot;
import static com.example.kairo.platform.command.ReconciliationTestSupport.persistSnapshotDirect;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedDesiredRule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedInstance;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedTrialSession;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-D &sect;8.4 item 7 / &sect;4.4: TTL-expired, trial and unpromoted script sessions never
 * enter the recovery (desired) set, so they are not revived after a JVM restart. Only formal,
 * still-valid rule versions (including those promoted from a trial) are re-applied.
 *
 * <p>Desired state is derived exclusively from {@code rule_runtime_status} joined to formal
 * {@code rule_version} (ENABLED); script sessions are never read for desired state, so an
 * unpromoted/expired trial that was live on the old JVM is structurally excluded.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1d_trial;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class ExpiredTrialDoesNotReviveIntegrationTest {

    private static final String PROCESS_START_ID = "trial-host:2:1700000000000";
    private static final String FORMAL_CLASS = "com.test.Svc";
    private static final String FORMAL_LOADER = "loader-1";
    private static final String TRIAL_CLASS = "com.test.Trial";

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
        agentId = "agent-trial-" + UUID.randomUUID();
        instanceId = "inst-trial-" + UUID.randomUUID();
        seedInstance(jdbc, agentId, instanceId, PROCESS_START_ID);
        systemContext = new RequestContext("system", "corr-trial", "127.0.0.1", "system", "test");
        // One formal rule, applied to the instance (the desired state).
        seedDesiredRule(jdbc, "rule-formal", 1, instanceId, FORMAL_CLASS, FORMAL_LOADER,
                "compute", "(I)I", "METHOD_ENTER",
                "{\"script\":\"return mock.returnValue(7)\",\"phase\":\"BEFORE\"}", "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        if (agentId != null) {
            jdbc.update("delete from script_session where agent_id = ?", agentId);
            jdbc.update("delete from degraded_class where agent_id = ?", agentId);
            jdbc.update("delete from agent_command where agent_id = ?", agentId);
            jdbc.update("delete from agent_runtime_state where agent_id = ?", agentId);
            jdbc.update("delete from agent_instance where id = ?", agentId);
        }
        if (instanceId != null) {
            jdbc.update("delete from rule_runtime_status where instance_id = ?", instanceId);
            jdbc.update("delete from instance where id = ?", instanceId);
        }
        jdbc.update("delete from rule_target where rule_version_id like 'rule-formal:%'");
        jdbc.update("delete from rule_version where rule_id = 'rule-formal'");
        jdbc.update("delete from rule where id = 'rule-formal'");
    }

    @Test
    void unpromotedAndExpiredTrialsAreNotRevivedAfterRestart() {
        // Before the restart, an unpromoted trial (APPLIED, no formal_rule_id) and an expired trial
        // were live on the old JVM. They are NOT formal rule versions.
        seedTrialSession(jdbc, "trial-applied", agentId, "APPLIED", TRIAL_CLASS, "trialMethod",
                "()V", 60_000L, "2099-01-01 00:00:00");
        seedTrialSession(jdbc, "trial-expired", agentId, "EXPIRED", TRIAL_CLASS, "trialMethod",
                "()V", 60_000L, "2020-01-01 00:00:00");
        // New JVM: empty actual snapshot (processStartId matches; nothing applied yet).
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                emptySnapshot(agentId, PROCESS_START_ID));

        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(systemContext, agentId);

        // Only the formal rule is re-applied; the trials are not revived.
        assertThat(result.applied()).isEqualTo(1);
        assertThat(result.reset()).isZero();
        assertThat(result.degraded()).isZero();
        List<Map<String, Object>> applies = applyRules();
        assertThat(applies).hasSize(1);
        assertThat(applies.get(0).get("payload_json").toString()).contains("rule-formal");
        // No APPLY_RULE targets the trial's class (the trial was not revived).
        assertThat(applies.get(0).get("payload_json").toString()).doesNotContain(TRIAL_CLASS);
        assertThat(applyRulesForClass(TRIAL_CLASS)).isEmpty();
        assertThat(resetsAllCommands()).isEmpty();
    }

    @Test
    void promotedTrialBecomesFormalAndIsRevived() {
        // A trial that was promoted created a formal rule_version + runtime status. It IS desired
        // and is revived (the script_session is just history now).
        seedDesiredRule(jdbc, "rule-promoted", 1, instanceId, "com.test.Promoted", "loader-2",
                "run", "()V", "METHOD_RETURN",
                "{\"script\":\"return mock.returnValue(9)\",\"phase\":\"BEFORE\"}", "ACTIVE");
        // The reverted trial session records the promotion (formal_rule_id set).
        jdbc.update("insert into script_session(id, agent_id, application_id, target_class_name, "
                + "target_method_name, target_method_descriptor, script_hash, requested_profile, "
                + "effective_profile, platform_max_profile, application_max_profile, policy_revision, "
                + "policy_hash, ttl_millis, max_hits, status, hit_count, version, idempotency_key, "
                + "requested_by, formal_rule_id, agent_result_json, diagnostics_json, created_at, "
                + "expires_at, reverted_at, updated_at, created_by, correlation_id) "
                + "values ('trial-promoted', ?, 'app-default', 'com.test.Promoted', 'run', '()V', 'h', "
                + "'SAFE','SAFE','UNRESTRICTED','UNRESTRICTED', 0, 'p', 60000, 100, 'REVERTED', 0, 1, "
                + "'idem-promoted', 'test', 'rule-promoted', '{}', '[]', current_timestamp, "
                + "'2020-01-01 00:00:00', current_timestamp, current_timestamp, 'test', 'corr')", agentId);
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                emptySnapshot(agentId, PROCESS_START_ID));

        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(systemContext, agentId);

        // Both formal rules (the directly-saved one and the promoted one) are revived.
        assertThat(result.applied()).isEqualTo(2);
        assertThat(applyRules()).hasSize(2);
        // Cleanup the promoted rule seeded here (FK order: runtime status before rule).
        jdbc.update("delete from rule_runtime_status where rule_id = 'rule-promoted'");
        jdbc.update("delete from rule_target where rule_version_id like 'rule-promoted:%'");
        jdbc.update("delete from rule_version where rule_id = 'rule-promoted'");
        jdbc.update("delete from rule where id = 'rule-promoted'");
    }

    private List<Map<String, Object>> applyRules() {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type = 'APPLY_RULE'",
                agentId);
    }

    private List<Map<String, Object>> applyRulesForClass(String className) {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type = 'APPLY_RULE' and payload_json like ?",
                agentId, "%" + className + "%");
    }

    private List<Map<String, Object>> resetsAllCommands() {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type = 'RESET_ALL'", agentId);
    }
}
