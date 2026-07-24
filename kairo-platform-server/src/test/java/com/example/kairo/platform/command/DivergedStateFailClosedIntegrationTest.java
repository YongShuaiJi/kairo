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

import static com.example.kairo.platform.command.ReconciliationTestSupport.chain;
import static com.example.kairo.platform.command.ReconciliationTestSupport.persistSnapshotDirect;
import static com.example.kairo.platform.command.ReconciliationTestSupport.rule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedDesiredRule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedInstance;
import static com.example.kairo.platform.command.ReconciliationTestSupport.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-D &sect;8.4 item 6 / &sect;4.4: AHEAD, DIVERGED and TARGET_DRIFTED actual states are
 * marked DEGRADED and <strong>never</strong> auto-destructive-overwritten. No {@code RESET_ALL}
 * is ever issued to hide drift, no blind {@code RESET_CLASS} on an unknown chain, and no
 * {@code APPLY_RULE} re-issued over a diverged chain. The operator resolves these through the
 * existing re-publish / unload operations.
 *
 * <p>A dedicated in-memory H2 isolates the test; the reconciliation scheduler is disabled in the
 * test profile so only the explicit {@code reconcileAgent} call runs.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1d_diverged;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class DivergedStateFailClosedIntegrationTest {

    private static final String PROCESS_START_ID = "diverged-host:1:1700000000000";
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
        agentId = "agent-div-" + UUID.randomUUID();
        instanceId = "inst-div-" + UUID.randomUUID();
        seedInstance(jdbc, agentId, instanceId, PROCESS_START_ID);
        systemContext = new RequestContext("system", "corr-div", "127.0.0.1", "system", "test");
        // Formal desired rule R, applied to com.test.Svc#compute.
        seedDesiredRule(jdbc, "rule-div", 1, instanceId, TARGET_CLASS, TARGET_LOADER,
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
        jdbc.update("delete from rule_target where rule_version_id like 'rule-div:%'");
        jdbc.update("delete from rule_version where rule_id = 'rule-div'");
        jdbc.update("delete from rule where id = 'rule-div'");
    }

    @Test
    void divergedChainWithExtraRuleIsMarkedDegradedWithoutDestructiveOverwrite() {
        // Actual carries the desired target's chain but with an extra rule not in desired -> DIVERGED.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                snapshot(agentId, PROCESS_START_ID,
                        chain("com.test.Svc#compute#METHOD_ENTER", TARGET_CLASS, TARGET_LOADER,
                                "compute", "(I)I", "METHOD_ENTER", "ACTIVE",
                                List.of("rule-div", "rule-unknown"), null),
                        List.of("rule-div", "rule-unknown"),
                        List.of(rule("rule-div", 1), rule("rule-unknown", 1))));

        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(systemContext, agentId);

        assertThat(result.degraded()).isEqualTo(1);
        assertThat(result.applied()).isZero();
        assertThat(result.reset()).isZero();
        // No destructive command of any kind was enqueued.
        assertThat(convergenceCommands()).isEmpty();
        assertThat(resetsAllCommands()).isEmpty();
        // The class is marked DEGRADED for operator attention.
        assertThat(degradedReason(TARGET_CLASS)).contains("DIVERGED");
    }

    @Test
    void aheadChainNotInDesiredIsMarkedDegradedWithoutBlindReset() {
        // Actual carries a chain for a target the Platform never desired -> AHEAD. No blind RESET_CLASS.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                snapshot(agentId, PROCESS_START_ID,
                        chain("com.test.Other#other#METHOD_RETURN", "com.test.Other", "loader-2",
                                "other", "()V", "METHOD_RETURN", "ACTIVE",
                                List.of("rule-stranger"), null),
                        List.of("rule-stranger"),
                        List.of(rule("rule-stranger", 1))));
        // The desired chain for com.test.Svc#compute is MISSING, so an APPLY_RULE is expected for it;
        // the unknown com.test.Other chain must NOT be reset.
        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(systemContext, agentId);

        assertThat(result.applied()).isEqualTo(1); // the missing desired rule is re-applied
        assertThat(result.degraded()).isEqualTo(1); // the unknown chain is DEGRADED, not reset
        assertThat(result.reset()).isZero();
        // No RESET_ALL ever; no RESET_CLASS on the unknown chain (only a RESET_CLASS would be
        // enqueued for a formally-removed target, which this is not).
        assertThat(resetsAllCommands()).isEmpty();
        assertThat(resetsClassForClass("com.test.Other")).isEmpty();
        assertThat(degradedReason("com.test.Other")).contains("AHEAD");
    }

    @Test
    void targetDriftedChainIsMarkedDegradedWithoutDestructiveOverwrite() {
        // The agent reports the desired chain as degraded (TARGET_DRIFTED). Reconciliation must not
        // re-apply over it or unload it.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                snapshot(agentId, PROCESS_START_ID,
                        chain("com.test.Svc#compute#METHOD_ENTER", TARGET_CLASS, TARGET_LOADER,
                                "compute", "(I)I", "METHOD_ENTER", "ACTIVE",
                                List.of("rule-div"), "TARGET_DRIFTED"),
                        List.of("rule-div"),
                        List.of(rule("rule-div", 1))));

        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(systemContext, agentId);

        assertThat(result.degraded()).isEqualTo(1);
        assertThat(result.applied()).isZero();
        assertThat(result.reset()).isZero();
        assertThat(convergenceCommands()).isEmpty();
        assertThat(degradedReason(TARGET_CLASS)).contains("TARGET_DRIFTED");
    }

    @Test
    void inSyncTargetClearsDegradedMarker() {
        // First pass: an AHEAD chain marks the class degraded.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                snapshot(agentId, PROCESS_START_ID,
                        chain("com.test.Other#other#METHOD_RETURN", "com.test.Other", "loader-2",
                                "other", "()V", "METHOD_RETURN", "ACTIVE",
                                List.of("rule-stranger"), null),
                        List.of("rule-stranger"),
                        List.of(rule("rule-stranger", 1))));
        reconciliation.reconcileAgent(systemContext, agentId);
        assertThat(degradedReason("com.test.Other")).isNotNull();
        // A fresh REFRESH shows the diverged chain gone (operator re-published / unloaded) and the
        // desired chain present and in sync -> the degraded marker must be cleared.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                snapshot(agentId, PROCESS_START_ID,
                        chain("com.test.Svc#compute#METHOD_ENTER", TARGET_CLASS, TARGET_LOADER,
                                "compute", "(I)I", "METHOD_ENTER", "ACTIVE",
                                List.of("rule-div"), null),
                        List.of("rule-div"),
                        List.of(rule("rule-div", 1))));

        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(systemContext, agentId);

        assertThat(result.degraded()).isZero();
        assertThat(degradedReason("com.test.Other")).isNull();
    }

    @Test
    void activeRuleWithoutTargetFailsClosedInsteadOfApplyingNullTarget() {
        jdbc.update("delete from rule_target where rule_version_id = 'rule-div:1'");
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                ReconciliationTestSupport.emptySnapshot(agentId, PROCESS_START_ID));

        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(systemContext, agentId);

        assertThat(result.applied()).isZero();
        assertThat(result.reset()).isZero();
        assertThat(convergenceCommands()).isEmpty();
        assertThat(result.notes()).anyMatch(note -> note.contains("INVALID_DESIRED_TARGET")
                && note.contains("rule-div"));
    }

    @Test
    void malformedActualSnapshotFailsClosedInsteadOfReapplyingEverything() {
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                ReconciliationTestSupport.emptySnapshot(agentId, PROCESS_START_ID));
        jdbc.update("update agent_runtime_state set snapshot_json = '{' where agent_id = ?", agentId);

        AgentReconciliationService.ReconciliationResult result =
                reconciliation.reconcileAgent(systemContext, agentId);

        assertThat(result.applied()).isZero();
        assertThat(result.reset()).isZero();
        assertThat(convergenceCommands()).isEmpty();
        assertThat(result.notes()).containsExactly(
                "INVALID_ACTUAL_SNAPSHOT: persisted runtime snapshot is malformed; reconciliation skipped");
    }

    private List<Map<String, Object>> convergenceCommands() {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type in ('APPLY_RULE','RESET_CLASS','RESET_ALL')",
                agentId);
    }

    private List<Map<String, Object>> resetsAllCommands() {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type = 'RESET_ALL'", agentId);
    }

    private List<Map<String, Object>> resetsClassForClass(String className) {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type = 'RESET_CLASS' and payload_json like ?",
                agentId, "%" + className + "%");
    }

    private String degradedReason(String className) {
        List<String> reasons = jdbc.queryForList(
                "select reason from degraded_class where agent_id = ? and class_name = ?",
                String.class, agentId, className);
        return reasons.isEmpty() ? null : reasons.get(0);
    }
}
