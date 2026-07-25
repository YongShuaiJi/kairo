package com.example.kairo.agent.server;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.platform.KairoPlatformApplication;
import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.command.AgentReconciliationService;
import com.example.kairo.platform.fencing.FencingTokenService;
import com.example.kairo.platform.rollout.RuleUnloadService;
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
import java.util.concurrent.atomic.AtomicLong;

import static com.example.kairo.platform.command.ReconciliationTestSupport.emptySnapshot;
import static com.example.kairo.platform.command.ReconciliationTestSupport.persistSnapshotDirect;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedDesiredRule;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedExecution;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedInstance;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedSucceededOperation;
import static com.example.kairo.platform.command.ReconciliationTestSupport.seedTrialSession;
import static com.example.kairo.platform.command.ReconciliationTestSupport.setAgentOffline;
import static com.example.kairo.platform.command.ReconciliationTestSupport.setAgentOnline;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1 &sect;8.8 overall acceptance: one real-JVM test that proves the entire required recovery
 * chain end to end on a single instrumented JVM:
 *
 * <ol>
 *   <li>Platform desired state (formal rule {@code R}) &rarr; Agent command ({@code APPLY_RULE})
 *       &rarr; live JVM enhancement ({@code compute(7)} 14&rarr;42) &rarr; ACK;</li>
 *   <li>simulated {@code AgentRuntime}/JVM restart with a new {@code processStartId}
 *       &rarr; real runtime snapshot (empty) &rarr; reconciliation re-applies {@code R} (14&rarr;42);</li>
 *   <li>Platform disconnect while the live JVM stays enhanced &rarr; unload submitted as offline
 *       compensation ({@code OFFLINE_PENDING}, not a fabricated {@code UNLOADED});</li>
 *   <li>reconnect &rarr; real snapshot (chain present) &rarr; precise reconciliation unload
 *       ({@code RESET_CLASS}) &rarr; ACK &rarr; original method behavior restored (42&rarr;14).</li>
 * </ol>
 *
 * <p>Asserts terminal operation/runtime state and that no trial (unpromoted) rule is revived and no
 * destructive {@code RESET_ALL} is ever issued. This is the single closed loop required by
 * &sect;8.8; it composes the M1-D restart-reapply behavior and the M1-E disconnect/unload
 * compensation behavior on one real JVM rather than re-implementing either.
 *
 * <p>Lives in {@code com.example.kairo.agent.server} so it can construct the package-private
 * {@link PlatformCommandPoller} and drive its {@code execute} on a real instrumented JVM.
 */
@SpringBootTest(classes = KairoPlatformApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1_closedloop;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
class M1ClosedLoopRecoveryIntegrationTest {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final String RULE_ID = "rule-closedloop";
    private static final long RULE_VERSION = 1L;
    private static final String TARGET_CLASS = RealAgentTrialTarget.class.getName();
    private static final String TARGET_METHOD = "compute";
    private static final String TARGET_DESCRIPTOR = "(I)I";
    private static final String SCRIPT_JSON = "{\"script\":\"return mock.returnValue(42)\",\"phase\":\"BEFORE\"}";
    private static final String TRIAL_TARGET_CLASS = "com.test.TrialTarget";

    @Autowired AgentCommandService commands;
    @Autowired AgentReconciliationService reconciliation;
    @Autowired RuleUnloadService unloadService;
    @Autowired FencingTokenService fencingTokens;
    @Autowired JdbcTemplate jdbc;

    private AgentRuntime runtime;
    private PlatformCommandPoller poller;
    private String agentId;
    private String instanceId;
    private String operationId;
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
        agentId = "agent-closedloop-" + n;
        instanceId = "inst-closedloop-" + n;
        operationId = "op-closedloop-" + n;
        p1 = "closedloop-host:" + n + ":1700000000001";
        p2 = "closedloop-host:" + n + ":1800000000002";
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
        // A succeeded rollout operation against R so an unload can be submitted later.
        seedSucceededOperation(jdbc, operationId, RULE_ID, RULE_VERSION);
        seedExecution(jdbc, "exec-closedloop-" + n, operationId, instanceId, RULE_VERSION, "SUCCEEDED");
        admin = new RequestContext("system", "corr-closedloop", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr-closedloop", "127.0.0.1", "agent", "test");
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
        jdbc.update("delete from rule_target where rule_version_id like '" + RULE_ID + ":%'");
        jdbc.update("delete from rule_version where rule_id = '" + RULE_ID + "'");
        jdbc.update("delete from rule where id = '" + RULE_ID + "'");
    }

    @Test
    void fullClosedLoopRecoverApplyRestartDisconnectUnloadRestore() throws Exception {
        // Baseline: the real target returns 14 for compute(7), untouched.
        assertThat(new RealAgentTrialTarget().compute(7)).isEqualTo(14);

        // An unpromoted trial session was live on the old JVM before any of this (history only);
        // it must never be revived by reconciliation across the whole closed loop.
        seedTrialSession(jdbc, "trial-closedloop", agentId, "APPLIED", TRIAL_TARGET_CLASS,
                "trialMethod", "()V", 60_000L, "2099-01-01 00:00:00");

        // --- Phase 1: desired state -> APPLY_RULE -> live JVM enhancement -> ACK. ---
        persistSnapshotDirect(jdbc, agentId, instanceId, p1, emptySnapshot(agentId, p1));
        reconciliation.reconcileAgent(admin, agentId); // desired R missing -> APPLY_RULE enqueued
        executeNextOnRealAgent(); // poll + applyRule + ack -> R applied on the real JVM
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("formal rule applied on the real JVM").isEqualTo(42);
        // A real REFRESH persists a snapshot showing R applied, for processStartId p1.
        executeRefreshOnRealAgent();
        assertThat(persistedSnapshotProcessStartId()).isEqualTo(p1);

        // --- Phase 2: simulated AgentRuntime/JVM restart with a new processStartId. ---
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

        // Real runtime snapshot (empty for p2) -> reconciliation/reapply.
        executeRefreshOnRealAgent(); // persists empty snapshot for p2; post-ack reconcile enqueues APPLY_RULE
        assertThat(persistedSnapshotProcessStartId()).isEqualTo(p2);
        executeNextOnRealAgent(); // poll + applyRule + ack -> R re-applied on the new JVM
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("formal rule re-applied after restart").isEqualTo(42);

        // The trial was NOT revived: every APPLY_RULE so far targets the formal rule's class only.
        assertNoTrialRevived("after restart reapply");
        // No RESET_ALL was ever issued to hide drift.
        assertThat(countCommands("RESET_ALL"))
                .as("no RESET_ALL before disconnect").isZero();

        // --- Phase 3: Platform disconnect while the live JVM remains enhanced. ---
        setAgentOffline(jdbc, agentId);
        // The rule is still applied in the JVM even though the Platform cannot reach the agent.
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("JVM alive while Platform disconnected").isEqualTo(42);

        // An unload submitted while disconnected is a pending compensation, NOT a fabricated unload.
        String token = String.valueOf(fencingTokens.issue(admin, "operation_plan", operationId,
                "closed loop unload", 0).get("token"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("expectedStatus", "SUCCEEDED");
        request.put("expectedVersion", 2L);
        request.put("reason", "closed loop disconnect unload");
        request.put("fencingToken", token);
        unloadService.unload(operationId, admin, request);
        assertThat(operationStatus()).isEqualTo("UNLOADING");
        assertThat(executionStatus()).isEqualTo("OFFLINE_PENDING");
        // The rule is still applied: no RESET_CLASS reached the JVM yet.
        assertThat(new RealAgentTrialTarget().compute(7)).isEqualTo(42);

        // --- Phase 4: reconnect -> real snapshot -> precise reconciliation unload -> ACK -> restored. ---
        setAgentOnline(jdbc, agentId);
        executeRefreshOnRealAgent(); // real snapshot: chain still present
        reconciliation.reconcileAgent(admin, agentId); // compensation dispatches precise RESET_CLASS
        executeNextOnRealAgent(); // poll + RESET_CLASS + ack -> rule unloaded on the real JVM

        // Original method behavior restored: the rule is gone, compute(7) is 14 again.
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("rule unloaded after reconnect").isEqualTo(14);

        // Terminal operation/runtime state.
        assertThat(operationStatus()).isEqualTo("UNLOADED");
        assertThat(ruleRuntimeStatus()).isEqualTo("REMOVED");

        // Across the whole closed loop: no trial revived, no destructive RESET_ALL.
        assertNoTrialRevived("after final unload");
        assertThat(countCommands("RESET_ALL"))
                .as("no RESET_ALL across the whole closed loop").isZero();
        // The precise unload was a RESET_CLASS (not a RESET_ALL) targeting R's class only.
        assertThat(countCommands("RESET_CLASS"))
                .as("precise RESET_CLASS unload was issued").isEqualTo(1);
        List<String> resetPayloads = jdbc.queryForList(
                "select payload_json from agent_command where agent_id = ? and command_type = 'RESET_CLASS'",
                String.class, agentId);
        assertThat(resetPayloads).hasSize(1);
        assertThat(resetPayloads.get(0)).contains(TARGET_CLASS);
        assertThat(resetPayloads.get(0)).doesNotContain(TRIAL_TARGET_CLASS);

        // --- Phase 5: a fresh REFRESH after the completed unload must stay observable. ---
        // The completed unload left no rule behind, so the real runtime snapshot carries no
        // chains and no rules. A lingering empty chain (blank chainId) here would be rejected by
        // the Platform REFRESH validator and break the M1 recovery invariant.
        AgentRuntimeSnapshot postUnload = executeRefreshOnRealAgent();
        assertThat(postUnload.chains())
                .as("no chains linger after the final rule is unloaded").isEmpty();
        assertThat(postUnload.rules())
                .as("no rules linger after the final rule is unloaded").isEmpty();

        // Re-running reconciliation against the empty actual + REMOVED desired state is a no-op:
        // zero actions applied, zero degraded results, zero pending compensations progressed.
        AgentReconciliationService.ReconciliationResult recon = reconciliation.reconcileAgent(admin, agentId);
        assertThat(recon.applied())
                .as("reconciliation applies nothing after a completed unload").isZero();
        assertThat(recon.degraded())
                .as("reconciliation reports no degraded results after a completed unload").isZero();
        assertThat(recon.compensated())
                .as("no pending unload compensation remains after a completed unload").isZero();
        assertThat(recon.reset()).isZero();
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

    private AgentRuntimeSnapshot executeRefreshOnRealAgent() throws Exception {
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
        return dto;
    }

    private void assertNoTrialRevived(String phase) {
        List<String> applyPayloads = jdbc.queryForList(
                "select payload_json from agent_command where agent_id = ? and command_type = 'APPLY_RULE'",
                String.class, agentId);
        assertThat(applyPayloads)
                .as("APPLY_RULE commands exist " + phase).isNotEmpty();
        assertThat(applyPayloads).allSatisfy(payload -> {
            assertThat(payload).contains(RULE_ID);
            assertThat(payload).doesNotContain(TRIAL_TARGET_CLASS);
        });
    }

    private long countCommands(String commandType) {
        Long count = jdbc.queryForObject(
                "select count(*) from agent_command where agent_id = ? and command_type = ?",
                Long.class, agentId, commandType);
        return count == null ? 0L : count;
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

    private String operationStatus() {
        return jdbc.queryForObject("select status from operation_plan where id = ?", String.class, operationId);
    }

    private String executionStatus() {
        return jdbc.queryForObject(
                "select status from rollout_instance_execution where operation_plan_id = ?",
                String.class, operationId);
    }

    private String ruleRuntimeStatus() {
        return jdbc.queryForObject(
                "select status from rule_runtime_status where rule_id = ? and instance_id = ?",
                String.class, RULE_ID, instanceId);
    }
}
