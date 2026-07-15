package com.example.kairo.agent.server;

import com.example.kairo.agent.core.AgentRuntime;
import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.automation.AutomationSessionStatus;
import com.example.kairo.core.ClassLoaderIdentity;
import com.example.kairo.core.MethodDescriptor;
import com.example.kairo.platform.KairoPlatformApplication;
import com.example.kairo.platform.automation.AutomationSessionService;
import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.6 acceptance safety: the AI lifecycle (AutomationSessionService trial = create+validate+apply,
 * then revert) driven through the <strong>real agent</strong> &mdash; {@link AgentRuntime} +
 * {@link PlatformCommandPoller} with live JVM instrumentation &mdash; rather than simulated
 * acknowledgements. The platform dispatches each {@code SCRIPT_SESSION_*} command to the
 * {@code agent_command} channel; this test polls that channel and hands each command to
 * {@link PlatformCommandPoller#execute(JsonNode)}, which compiles the script and redefines the
 * target method via ByteBuddy on the real JVM, then acks the structured result back so the
 * platform reconciles the session. The proof is behavioral: {@link RealAgentTrialTarget#compute(int)}
 * returns {@code 14} before the trial, {@code 42} while the enhancement is applied, and {@code 14}
 * again after revert &mdash; i.e. an actual method-behavior change and reliable revert, not a
 * stubbed ack.
 *
 * <p>Lives in the {@code com.example.kairo.agent.server} package so it can construct the
 * package-private {@link PlatformCommandPoller} and call its package-private {@code execute}.
 */
@SpringBootTest(classes = KairoPlatformApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_real_agent;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
class V16RealAgentAiLifecycleTest {

    @Autowired AutomationSessionService sessionService;
    @Autowired AgentCommandService commands;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final AtomicLong COUNTER = new AtomicLong();

    private AgentRuntime runtime;
    private PlatformCommandPoller poller;
    private String instanceId;
    private String agentId;
    private RequestContext admin;
    private RequestContext agentCtx;

    @BeforeEach
    void setUp() throws Exception {
        // Real JVM instrumentation + real agent runtime (no simulation).
        Instrumentation instrumentation = ByteBuddyAgent.install();
        runtime = new AgentRuntime(instrumentation);
        runtime.start();
        poller = new PlatformCommandPoller(runtime, AgentLaunchConfig.parse(""), () -> { });

        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        long n = COUNTER.incrementAndGet();
        instanceId = "instance-real-agent-" + n;
        agentId = "agent-real-agent-" + n;
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, created_at, updated_at)
                values (?, 'app-default', 'env-dev', 'real-agent', 'localhost', '1', 'java',
                  'ACTIVE', '{}', current_timestamp, current_timestamp)
                """, instanceId);
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values (?, ?, 'ACTIVE', 'test', 'test', '127.0.0.1', 1, 'hash', '[]',
                  current_timestamp, current_timestamp)
                """, agentId, instanceId);
        admin = new RequestContext("system", "corr-real", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr-real", "127.0.0.1", "agent", "test");
    }

    @AfterEach
    void tearDown() {
        // Restore any instrumentation that may remain if a test failed before revert, so it does
        // not leak into other tests on the same class loader.
        try {
            if (runtime != null) {
                runtime.resetAll("test");
            }
        } catch (RuntimeException ignored) {
            // best-effort cleanup
        }
        if (poller != null) {
            poller.close();
        }
        if (runtime != null) {
            runtime.close();
        }
        jdbc.update("delete from operation_event where operation_id in (select id from operation where automation_session_id in (select id from automation_session where caller like 'ai-real%'))");
        jdbc.update("delete from operation where automation_session_id in (select id from automation_session where caller like 'ai-real%')");
        jdbc.update("delete from automation_session_resource where session_id in (select id from automation_session where caller like 'ai-real%')");
        jdbc.update("delete from automation_session where caller like 'ai-real%'");
        jdbc.update("delete from script_session_event where session_id in (select id from script_session where agent_id = ?)", agentId);
        jdbc.update("delete from script_session where agent_id = ?", agentId);
        jdbc.update("delete from agent_command where agent_id = ?", agentId);
        jdbc.update("delete from agent_instance where id = ?", agentId);
        jdbc.update("delete from instance where id = ?", instanceId);
    }

    @Test
    void trialChangesMethodBehaviorAndRevertRestoresIt() throws Exception {
        // Baseline: the real target method returns 14 for compute(7), untouched by any enhancement.
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("baseline: compute(7) == 14 before any enhancement").isEqualTo(14);

        // 1. Create the automation session (AI narrows to SAFE).
        var session = sessionService.create(admin, new AutomationSessionService.CreateRequest(
                "ai-real", "mcp", "app-default", "env-dev", null, agentId,
                CapabilityProfile.SAFE, 600_000L));
        assertThat(session.status()).isEqualTo(AutomationSessionStatus.CREATED);

        // 2. Trial = create + validate + apply through the REAL agent (3 dispatched commands).
        //    The enhancement overrides compute to return 42.
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("className", RealAgentTrialTarget.class.getName());
        target.put("classLoaderId",
                ClassLoaderIdentity.idOf(RealAgentTrialTarget.class.getClassLoader()));
        java.lang.reflect.Method method = RealAgentTrialTarget.class.getMethod("compute", int.class);
        target.put("methodName", method.getName());
        target.put("methodDescriptor", MethodDescriptor.of(method));
        Map<String, Object> trial = runWithRealAgent(3, () -> sessionService.trial(admin, session.sessionId(),
                new AutomationSessionService.TrialRequest(target, "return mock.returnValue(42)",
                        CapabilityProfile.SAFE, 60_000L, 10L)));
        assertThat(trial.get("status"))
                .as("the real agent applied the trial (APPLIED, not a simulated ack)").isEqualTo("APPLIED");

        // 3. The real JVM behavior CHANGED: compute(7) is now 42 (the enhancement is live).
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("behavior changed: compute(7) == 42 while the enhancement is applied").isEqualTo(42);

        // 4. Revert through the REAL agent (1 dispatched command): the trial rule is removed.
        var reverted = runWithRealAgent(1, () -> sessionService.revert(admin, session.sessionId()));
        assertThat(reverted.status()).isEqualTo(AutomationSessionStatus.REVERTED);

        // 5. The real JVM behavior is RESTORED: compute(7) is 14 again.
        assertThat(new RealAgentTrialTarget().compute(7))
                .as("behavior restored: compute(7) == 14 after revert").isEqualTo(14);
    }

    /**
     * Run a service call that dispatches {@code expectedCommands} agent commands, driving each
     * through the REAL agent ({@link PlatformCommandPoller#execute}) instead of a stubbed ack.
     */
    private <T> T runWithRealAgent(int expectedCommands, Supplier<T> call) throws Exception {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(call::get);
        for (int i = 0; i < expectedCommands; i++) {
            ackNextWithRealAgent(future);
        }
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new AssertionError(e);
        }
    }

    /** Poll the next dispatched command, execute it on the real JVM, and ack the structured result. */
    private void ackNextWithRealAgent(CompletableFuture<?> future) {
        Map<String, Object> polled = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> candidate = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 10));
            if (!"NO_COMMAND".equals(candidate.get("status"))) {
                polled = candidate;
                break;
            }
            if (future.isDone()) {
                return; // the call finished without dispatching another command
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (polled == null) {
            Integer cmdCount = jdbc.queryForObject(
                    "select count(*) from agent_command where agent_id = ?", Integer.class, agentId);
            String cause = "no command dispatched";
            if (future.isDone()) {
                try {
                    future.get();
                } catch (Exception e) {
                    cause = "async call failed: " + e.getCause();
                }
            }
            throw new AssertionError("real agent: no command dispatched within timeout: " + cause
                    + " (agent_command count=" + cmdCount + ")");
        }
        String commandId = String.valueOf(polled.get("id"));
        JsonNode command = mapper.valueToTree(Map.of("payload", polled.get("payload")));
        try {
            Map<String, Object> result = poller.execute(command);
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("status", "ACKED");
            ack.put("result", result);
            ack.put("reason", "real agent executed on live JVM");
            commands.ack(commandId, agentCtx, ack);
        } catch (Exception e) {
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("status", "FAILED");
            ack.put("errorMessage", e.getClass().getSimpleName() + ": " + e.getMessage());
            ack.put("reason", "real agent execution failed");
            try {
                commands.ack(commandId, agentCtx, ack);
            } catch (RuntimeException ignored) {
                // best-effort: surface the original failure
            }
            throw new AssertionError("real agent poller.execute failed for "
                    + polled.get("command_type") + ": " + e, e);
        }
    }
}
