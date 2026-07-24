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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.example.kairo.platform.command.ReconciliationTestSupport.chain;
import static com.example.kairo.platform.command.ReconciliationTestSupport.emptySnapshot;
import static com.example.kairo.platform.command.ReconciliationTestSupport.persistSnapshotDirect;
import static com.example.kairo.platform.command.ReconciliationTestSupport.rule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedDesiredRule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedInstance;
import static com.example.kairo.platform.command.ReconciliationTestSupport.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-D &sect;8.4 items 2, 8: a same-{@code processStartId} reconnect preserves the actual
 * snapshot history, continues unfinished compensation (the in-flight guard skips a target whose
 * convergence command is still non-terminal), and a re-run after convergence is a no-op. No
 * command storm: concurrent/repeated reconciliation never enqueues a second APPLY_RULE for a key
 * that is already in flight.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1d_reconnect;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class AgentReconnectReconciliationIntegrationTest {

    private static final String PROCESS_START_ID = "reconnect-host:3:1700000000000";
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
        agentId = "agent-reconnect-" + UUID.randomUUID();
        instanceId = "inst-reconnect-" + UUID.randomUUID();
        seedInstance(jdbc, agentId, instanceId, PROCESS_START_ID);
        systemContext = new RequestContext("system", "corr-reconnect", "127.0.0.1", "system", "test");
        seedDesiredRule(jdbc, "rule-rc", 1, instanceId, TARGET_CLASS, TARGET_LOADER,
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
        jdbc.update("delete from rule_target where rule_version_id like 'rule-rc:%'");
        jdbc.update("delete from rule_version where rule_id = 'rule-rc'");
        jdbc.update("delete from rule where id = 'rule-rc'");
    }

    @Test
    void inSyncSnapshotIsNoOpOnReRun() {
        // Actual carries the desired chain in sync.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                snapshot(agentId, PROCESS_START_ID,
                        chain("com.test.Svc#compute#METHOD_ENTER", TARGET_CLASS, TARGET_LOADER,
                                "compute", "(I)I", "METHOD_ENTER", "ACTIVE",
                                List.of("rule-rc"), null),
                        List.of("rule-rc"),
                        List.of(rule("rule-rc", 1))));

        AgentReconciliationService.ReconciliationResult first =
                reconciliation.reconcileAgent(systemContext, agentId);
        AgentReconciliationService.ReconciliationResult second =
                reconciliation.reconcileAgent(systemContext, agentId);

        assertThat(first.applied()).isZero();
        assertThat(first.degraded()).isZero();
        assertThat(second.applied()).isZero(); // item 8: re-run after convergence is a no-op
        assertThat(second.degraded()).isZero();
        assertThat(convergenceCommands()).isEmpty();
        assertThat(snapshotCount()).isEqualTo(1); // actual history preserved
    }

    @Test
    void inFlightApplyIsNotReEnqueuedOnReconnect() {
        // Drift: the actual snapshot lost the desired chain (e.g. an in-memory rule was evicted).
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                emptySnapshot(agentId, PROCESS_START_ID));

        AgentReconciliationService.ReconciliationResult first =
                reconciliation.reconcileAgent(systemContext, agentId);
        assertThat(first.applied()).isEqualTo(1); // APPLY_RULE enqueued for the missing rule
        assertThat(convergenceCommands()).hasSize(1);
        assertThat(commandStatus(firstCommandId())).isEqualTo("PENDING");

        // Reconnect (same processStartId) before the APPLY acks: the unfinished compensation is
        // continued, NOT re-enqueued. No command storm.
        AgentReconciliationService.ReconciliationResult second =
                reconciliation.reconcileAgent(systemContext, agentId);
        assertThat(second.applied()).isZero(); // in-flight guard skipped the re-enqueue
        assertThat(convergenceCommands()).hasSize(1); // still exactly one APPLY_RULE
        assertThat(snapshotCount()).isEqualTo(1); // actual history preserved
    }

    @Test
    void sameProcessStartIdReconnectPreservesActualAndConvergesAfterAck() {
        // Drift -> APPLY_RULE enqueued.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                emptySnapshot(agentId, PROCESS_START_ID));
        reconciliation.reconcileAgent(systemContext, agentId);
        String applyId = firstCommandId();
        assertThat(commandStatus(applyId)).isEqualTo("PENDING");

        // The agent applies the rule and the next REFRESH reports the chain present and in sync.
        persistSnapshotDirect(jdbc, agentId, instanceId, PROCESS_START_ID,
                snapshot(agentId, PROCESS_START_ID,
                        chain("com.test.Svc#compute#METHOD_ENTER", TARGET_CLASS, TARGET_LOADER,
                                "compute", "(I)I", "METHOD_ENTER", "ACTIVE",
                                List.of("rule-rc"), null),
                        List.of("rule-rc"),
                        List.of(rule("rule-rc", 1))));

        AgentReconciliationService.ReconciliationResult after =
                reconciliation.reconcileAgent(systemContext, agentId);

        assertThat(after.applied()).isZero(); // now IN_SYNC: no new APPLY_RULE
        assertThat(after.degraded()).isZero();
        // Still only the original APPLY_RULE; no storm across the reconnect/refresh cycle.
        assertThat(convergenceCommands()).hasSize(1);
        assertThat(snapshotCount()).isEqualTo(1);
    }

    @Test
    void concurrentRegistrationsDedupeButLaterSameProcessReconnectRequestsFreshSnapshot() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                await(start);
                reconciliation.onAgentRegistered(systemContext, agentId);
            });
            Future<?> second = executor.submit(() -> {
                await(start);
                reconciliation.onAgentRegistered(systemContext, agentId);
            });
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(refreshCommands()).hasSize(1);
        String firstRefreshId = String.valueOf(refreshCommands().get(0).get("id"));
        jdbc.update("""
                update agent_command
                   set status = 'ACKED', completed_at = current_timestamp,
                       updated_at = current_timestamp
                 where id = ?
                """, firstRefreshId);

        // A later registration of the same JVM must not be deduped against the old terminal
        // request: it needs a new snapshot to observe changes made since the first registration.
        reconciliation.onAgentRegistered(systemContext, agentId);

        assertThat(refreshCommands()).hasSize(2);
        assertThat(refreshCommands())
                .extracting(row -> String.valueOf(row.get("idempotency_key")))
                .doesNotHaveDuplicates()
                .allMatch(key -> key.startsWith(
                        AgentReconciliationService.refreshIdempotencyKey(agentId, PROCESS_START_ID)
                                + ":request:"));
    }

    private List<Map<String, Object>> convergenceCommands() {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type in ('APPLY_RULE','RESET_CLASS','RESET_ALL')",
                agentId);
    }

    private List<Map<String, Object>> refreshCommands() {
        return jdbc.queryForList(
                "select * from agent_command where agent_id = ? and command_type = 'REFRESH_RUNTIME_STATE' "
                        + "order by created_at, id",
                agentId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while coordinating concurrent registration", e);
        }
    }

    private String firstCommandId() {
        return jdbc.queryForObject(
                "select id from agent_command where agent_id = ? and command_type = 'APPLY_RULE' order by created_at limit 1",
                String.class, agentId);
    }

    private String commandStatus(String id) {
        return jdbc.queryForObject("select status from agent_command where id = ?", String.class, id);
    }

    private long snapshotCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from agent_runtime_state where agent_id = ?", Long.class, agentId);
        return count == null ? 0L : count;
    }
}
