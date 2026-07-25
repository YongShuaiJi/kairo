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
import static com.example.kairo.platform.command.ReconciliationTestSupport.setAgentOffline;
import static com.example.kairo.platform.command.ReconciliationTestSupport.setAgentOnline;
import static com.example.kairo.platform.command.ReconciliationTestSupport.snapshot;
import static com.example.kairo.platform.command.ReconciliationTestSupport.chain;
import static com.example.kairo.platform.command.ReconciliationTestSupport.rule;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-E &sect;8.5 real-JVM behavior: while the Platform is disconnected but the target JVM
 * stays alive, a submitted unload is recorded as a pending compensation (not a fabricated UNLOADED).
 * On reconnect the compensation sweep reads the real actual snapshot, dispatches the precise
 * RESET_CLASS, and the agent unloads the rule &mdash; restoring the original method behavior
 * ({@link RealAgentTrialTarget#compute(int)} returns {@code 14} again).
 *
 * <p>Lives in {@code com.example.kairo.agent.server} so it can construct the package-private
 * {@link PlatformCommandPoller} and drive its {@code execute} on a real instrumented JVM.
 */
@SpringBootTest(classes = KairoPlatformApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1e_realjvmunload;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
class RealJvmDisconnectUnloadIntegrationTest {

    private static final AtomicLong COUNTER = new AtomicLong();
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private static final String RULE_ID = "rule-realunload";
    private static final long RULE_VERSION = 1L;
    private static final String TARGET_CLASS = RealAgentTrialTarget.class.getName();
    private static final String TARGET_METHOD = "compute";
    private static final String TARGET_DESCRIPTOR = "(I)I";
    private static final String SCRIPT_JSON = "{\"script\":\"return mock.returnValue(42)\",\"phase\":\"BEFORE\"}";

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
    private String processStartId;
    private RequestContext admin;
    private RequestContext agentCtx;

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
        agentId = "agent-realunload-" + n;
        instanceId = "inst-realunload-" + n;
        operationId = "op-realunload-" + n;
        processStartId = "realunload-host:" + n + ":1700000000001";
        Instrumentation instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
        poller = new PlatformCommandPoller(runtime,
                AgentLaunchConfig.parse("platformAgentId=" + agentId + ",platformProcessStartId=" + processStartId),
                () -> { });
        loaderId = ClassLoaderIdentity.idOf(RealAgentTrialTarget.class.getClassLoader());
        seedInstance(jdbc, agentId, instanceId, processStartId);
        seedDesiredRule(jdbc, RULE_ID, RULE_VERSION, instanceId, TARGET_CLASS, loaderId,
                TARGET_METHOD, TARGET_DESCRIPTOR, "METHOD_ENTER", SCRIPT_JSON, "ACTIVE");
        seedSucceededOperation(jdbc, operationId, RULE_ID, RULE_VERSION);
        seedExecution(jdbc, "exec-realunload-" + n, operationId, instanceId, RULE_VERSION, "SUCCEEDED");
        admin = new RequestContext("system", "corr-realunload", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr-realunload", "127.0.0.1", "agent", "test");
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
    void disconnectKeepsJvmAliveAndReconnectCompletesUnload() throws Exception {
        // Baseline: the real target returns 14 for compute(7).
        assertThat(new RealAgentTrialTarget().compute(7)).isEqualTo(14);

        // --- Phase 1: apply the formal rule on the real JVM (compute 14 -> 42). ---
        persistSnapshotDirect(jdbc, agentId, instanceId, processStartId, emptySnapshot(agentId, processStartId));
        reconciliation.reconcileAgent(admin, agentId); // desired rule missing -> APPLY_RULE
        executeNextOnRealAgent();
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("formal rule applied on the real JVM").isEqualTo(42);
        // A real REFRESH persists a snapshot showing the chain present.
        executeRefreshOnRealAgent();

        // --- Phase 2: the Platform is disconnected (agent lease expired), JVM stays alive. ---
        setAgentOffline(jdbc, agentId);
        // The rule is still applied in the JVM even though the Platform cannot reach the agent.
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("JVM alive while Platform disconnected").isEqualTo(42);

        // An unload submitted while disconnected is a pending compensation, NOT a fabricated unload.
        String token = String.valueOf(fencingTokens.issue(admin, "operation_plan", operationId,
                "real unload", 0).get("token"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("expectedStatus", "SUCCEEDED");
        request.put("expectedVersion", 2L);
        request.put("reason", "disconnect unload");
        request.put("fencingToken", token);
        unloadService.unload(operationId, admin, request);
        assertThat(operationStatus()).isEqualTo("UNLOADING");
        assertThat(executionStatus()).isEqualTo("OFFLINE_PENDING");
        // The rule is still applied: no RESET_CLASS reached the JVM.
        assertThat(new RealAgentTrialTarget().compute(7)).isEqualTo(42);

        // --- Phase 3: reconnect. The compensation sweep reads the actual snapshot (chain present)
        //     and dispatches the precise RESET_CLASS; the agent unloads the rule. ---
        setAgentOnline(jdbc, agentId);
        executeRefreshOnRealAgent(); // real snapshot: chain still present
        reconciliation.reconcileAgent(admin, agentId);
        executeNextOnRealAgent(); // poll + RESET_CLASS + ack -> rule unloaded on the real JVM

        // Method behavior restored: the rule is gone, compute(7) is 14 again.
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("rule unloaded after reconnect").isEqualTo(14);
        assertThat(operationStatus()).isEqualTo("UNLOADED");
        assertThat(ruleRuntimeStatus()).isEqualTo("REMOVED");
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
        ack.put("reason", "real agent on live JVM");
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
        Map<String, Object> snapshotResult = poller.execute(command);
        AgentRuntimeSnapshot dto = MAPPER.convertValue(snapshotResult, AgentRuntimeSnapshot.class);
        assertThat(dto.processStartId()).isEqualTo(processStartId);
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", "ACKED");
        ack.put("expectedAttempts", attempts);
        ack.put("reason", "real refresh");
        ack.put("result", snapshotResult);
        commands.ack(commandId, agentCtx, ack);
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
