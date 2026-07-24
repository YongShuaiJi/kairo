package com.example.kairo.agent.server;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.platform.KairoPlatformApplication;
import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.command.AgentReconciliationService;
import com.example.kairo.platform.service.RequestContext;
import com.example.realagent.RealAgentTrialTarget;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.example.kairo.platform.command.ReconciliationTestSupport.persistSnapshotDirect;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedDesiredRule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedInstance;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedTrialSession;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-D &sect;8.4 real-JVM behavior: after an {@code AgentRuntime} restart (new
 * {@code processStartId}), the persisted actual snapshot is superseded and read as empty, formal
 * desired rules are re-applied on the real JVM, and a trial (unpromoted) rule is NOT revived. The
 * proof is behavioral: {@link RealAgentTrialTarget#compute(int)} returns {@code 42} while the
 * formal rule is applied, {@code 14} after the restart (the rule is gone), and {@code 42} again
 * once reconciliation re-applies it.
 *
 * <p>Lives in {@code com.example.kairo.agent.server} so it can construct the package-private
 * {@link PlatformCommandPoller} and drive its {@code execute} on a real instrumented JVM.
 */
@SpringBootTest(classes = KairoPlatformApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1d_jvmrestart;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
class JvmRestartReapplyIntegrationTest {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final String RULE_ID = "rule-jvmrestart";
    private static final long RULE_VERSION = 1L;
    private static final String TARGET_CLASS = RealAgentTrialTarget.class.getName();
    private static final String TARGET_METHOD = "compute";
    private static final String TARGET_DESCRIPTOR = "(I)I";
    private static final String SCRIPT_JSON = "{\"script\":\"return mock.returnValue(42)\",\"phase\":\"BEFORE\"}";

    @Autowired AgentCommandService commands;
    @Autowired AgentReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;

    private AgentRuntime runtime;
    private PlatformCommandPoller poller;
    private String agentId;
    private String instanceId;
    private String loaderId;
    private RequestContext admin;
    private RequestContext agentCtx;
    private String p1;
    private String p2;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("insert into project(id, organization_id, name, created_at) "
                + "select 'proj-default','org-default','Default Project',current_timestamp "
                + "where not exists (select 1 from project where id='proj-default')");
        jdbc.update("insert into application(id, project_id, name, created_at) "
                + "select 'app-default','proj-default','Default Application',current_timestamp "
                + "where not exists (select 1 from application where id='app-default')");
        jdbc.update("insert into environment(id, application_id, name, type, created_at) "
                + "select 'env-dev','app-default','dev','dev',current_timestamp "
                + "where not exists (select 1 from environment where id='env-dev')");
        long n = COUNTER.incrementAndGet();
        agentId = "agent-jvmrestart-" + n;
        instanceId = "inst-jvmrestart-" + n;
        p1 = "jvm-host:" + n + ":1700000000001";
        p2 = "jvm-host:" + n + ":1800000000002";
        Instrumentation instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
        poller = new PlatformCommandPoller(runtime,
                AgentLaunchConfig.parse("platformAgentId=" + agentId + ",platformProcessStartId=" + p1),
                () -> { });
        loaderId = ClassLoaderIdentity.idOf(RealAgentTrialTarget.class.getClassLoader());
        seedInstance(jdbc, agentId, instanceId, p1);
        // Desired formal rule R, applied to RealAgentTrialTarget#compute.
        seedDesiredRule(jdbc, RULE_ID, RULE_VERSION, instanceId, TARGET_CLASS, loaderId,
                TARGET_METHOD, TARGET_DESCRIPTOR, "METHOD_ENTER", SCRIPT_JSON, "ACTIVE");
        admin = new RequestContext("system", "corr-jvm", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr-jvm", "127.0.0.1", "agent", "test");
    }

    @AfterEach
    void tearDown() {
        try {
            if (runtime != null) {
                runtime.resetAll("test");
            }
        } catch (RuntimeException ignored) {
            // best-effort instrumentation cleanup
        }
        if (poller != null) {
            poller.close();
        }
        if (runtime != null) {
            runtime.close();
        }
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
        jdbc.update("delete from rule_target where rule_version_id like '" + RULE_ID + ":%'");
        jdbc.update("delete from rule_version where rule_id = '" + RULE_ID + "'");
        jdbc.update("delete from rule where id = '" + RULE_ID + "'");
    }

    @Test
    void jvmRestartReappliesFormalRuleAndDoesNotReviveTrial() throws Exception {
        // Baseline: the real target method returns 14 for compute(7), untouched.
        assertThat(new RealAgentTrialTarget().compute(7)).isEqualTo(14);

        // An unpromoted trial session was live on the old JVM before the restart (history only).
        seedTrialSession(jdbc, "trial-jvm", agentId, "APPLIED", "com.test.TrialTarget",
                "trialMethod", "()V", 60_000L, "2099-01-01 00:00:00");

        // --- Phase 1: the formal rule is applied on the real JVM (compute 14 -> 42). ---
        persistSnapshotDirect(jdbc, agentId, instanceId, p1,
                com.example.kairo.platform.command.ReconciliationTestSupport.emptySnapshot(agentId, p1));
        reconciliation.reconcileAgent(admin, agentId); // desired R missing -> APPLY_RULE enqueued
        executeNextOnRealAgent(); // poll + applyRule + ack -> R applied
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("formal rule applied on the real JVM").isEqualTo(42);
        // A real REFRESH persists a snapshot showing R applied, for processStartId p1.
        executeRefreshOnRealAgent();
        assertThat(persistedSnapshotProcessStartId()).isEqualTo(p1);

        // --- Phase 2: JVM restart (new processStartId). The in-memory rule is gone. ---
        runtime.resetAll("test");
        runtime.close();
        poller.close();
        Instrumentation instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
        poller = new PlatformCommandPoller(runtime,
                AgentLaunchConfig.parse("platformAgentId=" + agentId + ",platformProcessStartId=" + p2),
                () -> { });
        jdbc.update("update instance set process_start_id = ? where id = ?", p2, instanceId);
        // The new JVM lost the rule: compute(7) is 14 again.
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("restart lost the in-memory rule").isEqualTo(14);

        // A real REFRESH on the new (empty) JVM persists an empty snapshot for p2; the post-ack
        // reconciliation sees desired R missing from the empty actual and re-applies it.
        executeRefreshOnRealAgent();
        assertThat(persistedSnapshotProcessStartId()).isEqualTo(p2);
        // The old snapshot (p1) was superseded: reconciliation re-applies R.
        executeNextOnRealAgent(); // poll + applyRule + ack -> R re-applied on the new JVM
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("formal rule re-applied after restart").isEqualTo(42);

        // The trial was NOT revived: every APPLY_RULE targets the formal rule's class only.
        List<String> applyPayloads = jdbc.queryForList(
                "select payload_json from agent_command where agent_id = ? and command_type = 'APPLY_RULE'",
                String.class, agentId);
        assertThat(applyPayloads).isNotEmpty();
        assertThat(applyPayloads).allSatisfy(payload -> {
            assertThat(payload).contains(RULE_ID);
            assertThat(payload).doesNotContain("com.test.TrialTarget");
        });
        // No RESET_ALL was ever issued to hide drift.
        Long resetsAll = jdbc.queryForObject(
                "select count(*) from agent_command where agent_id = ? and command_type = 'RESET_ALL'",
                Long.class, agentId);
        assertThat(resetsAll).isZero();

        // A final real REFRESH reports the chain present and in sync; reconciliation is a no-op.
        executeRefreshOnRealAgent();
        AgentReconciliationService.ReconciliationResult after =
                reconciliation.reconcileAgent(admin, agentId);
        assertThat(after.applied()).isZero();
        assertThat(after.degraded()).isZero();
    }

    private void executeNextOnRealAgent() throws Exception {
        Map<String, Object> polled = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        assertThat(polled.get("status")).isEqualTo("DISPATCHED");
        long attempts = ((Number) polled.get("attempts")).longValue();
        JsonNode command = MAPPER.valueToTree(Map.of("payload", polled.get("payload")));
        Map<String, Object> result = poller.execute(command);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", "ACKED");
        ack.put("expectedAttempts", attempts);
        ack.put("reason", "real agent applied on live JVM");
        ack.put("result", result);
        commands.ack(String.valueOf(polled.get("id")), agentCtx, ack);
    }

    private void executeRefreshOnRealAgent() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", "REFRESH_RUNTIME_STATE");
        request.put("maxAttempts", 5);
        String commandId = String.valueOf(commands.createManualCommand(admin, agentId, request).get("id"));
        Map<String, Object> polled = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        assertThat(polled.get("id")).isEqualTo(commandId);
        long attempts = ((Number) polled.get("attempts")).longValue();
        JsonNode command = MAPPER.valueToTree(Map.of(
                "payload", Map.of("commandType", "REFRESH_RUNTIME_STATE")));
        Map<String, Object> snapshot = poller.execute(command);
        // Sanity: the real snapshot carries the processStartId currently registered.
        AgentRuntimeSnapshot dto = MAPPER.convertValue(snapshot, AgentRuntimeSnapshot.class);
        assertThat(dto.processStartId()).isEqualTo(currentProcessStartId());
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", "ACKED");
        ack.put("expectedAttempts", attempts);
        ack.put("reason", "real refresh");
        ack.put("result", snapshot);
        commands.ack(commandId, agentCtx, ack);
    }

    private String currentProcessStartId() {
        // The test sets p1 then p2; the active one matches the instance's column.
        return jdbc.queryForObject("select process_start_id from instance where id = ?",
                String.class, instanceId);
    }

    private String persistedSnapshotProcessStartId() {
        return jdbc.queryForObject(
                "select process_start_id from agent_runtime_state where agent_id = ?",
                String.class, agentId);
    }
}
