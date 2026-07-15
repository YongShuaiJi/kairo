package com.example.kairo.platform;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.automation.AutomationSessionStatus;
import com.example.kairo.platform.automation.AutomationSessionService;
import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.6 &sect;9.6 AI trial reconciliation: drives {@link AutomationSessionService#trial}
 * (which delegates to {@code ScriptSessionService.create}+{@code apply}) and
 * {@link AutomationSessionService#revert} through the real agent command channel, acking each
 * dispatched command with a simulated result. This proves the platform-side trial+revert
 * state machine and resource bookkeeping, but the agent acks are simulated (no live agent),
 * so it does <em>not</em> assert any real method-behavior change. That proof &mdash; real
 * {@code AgentRuntime} instrumentation driving the same lifecycle with an actual
 * compute()-behavior change and revert &mdash; lives in
 * {@code com.example.kairo.agent.server.V16RealAgentAiLifecycleTest}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_ai_trial;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
class V16AiTrialRealJvmTest {

    @Autowired AutomationSessionService sessionService;
    @Autowired AgentCommandService commands;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;

    private static final AtomicLong COUNTER = new AtomicLong();

    private String instanceId;
    private String agentId;
    private RequestContext admin;
    private RequestContext agentCtx;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        long n = COUNTER.incrementAndGet();
        instanceId = "instance-ai-trial-" + n;
        agentId = "agent-ai-trial-" + n;
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, created_at, updated_at)
                values (?, 'app-default', 'env-dev', 'ai-trial', 'localhost', '1', 'java',
                  'ACTIVE', '{}', current_timestamp, current_timestamp)
                """, instanceId);
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values (?, ?, 'ACTIVE', 'test', 'test', '127.0.0.1', 1, 'hash', '[]',
                  current_timestamp, current_timestamp)
                """, agentId, instanceId);
        admin = new RequestContext("system", "corr", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr", "127.0.0.1", "agent", "test");
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from automation_session_resource where session_id in (select id from automation_session where caller like 'ai-trial%')");
        jdbc.update("delete from operation_event where operation_id in (select id from automation_session where caller like 'ai-trial%')");
        jdbc.update("delete from automation_session where caller like 'ai-trial%'");
        jdbc.update("delete from script_session_event where session_id in (select id from script_session where agent_id = ?)", agentId);
        jdbc.update("delete from script_session where agent_id = ?", agentId);
        jdbc.update("delete from agent_command where agent_id = ?", agentId);
        jdbc.update("delete from agent_instance where id = ?", agentId);
        jdbc.update("delete from instance where id = ?", instanceId);
    }

    @Test
    void aiTrialAppliesAndRevertsThroughRealAgentChannel() throws Exception {
        var session = sessionService.create(admin, new AutomationSessionService.CreateRequest(
                "ai-trial", "mcp", "app-default", "env-dev", null, agentId,
                CapabilityProfile.SAFE, 600_000L));
        assertThat(session.status()).isEqualTo(AutomationSessionStatus.CREATED);

        Map<String, Object> target = new LinkedHashMap<>();
        target.put("className", "com.example.kairo.platform.V16AiTrialRealJvmTest");
        target.put("classLoaderId", "bootstrap");
        target.put("methodName", "trialApplies");
        target.put("methodDescriptor", "()V");

        // trial = create (ack CREATED) + apply (ack APPLIED), driven concurrently with simulated acks.
        var trialFuture = CompletableFuture.supplyAsync(() ->
                sessionService.trial(admin, session.sessionId(),
                        new AutomationSessionService.TrialRequest(target, "ctx.result = 1\n",
                                CapabilityProfile.SAFE, 60_000L, 1L)));
        ackNext(trialFuture, Map.of("status", "CREATED", "hitCount", 0));    // SCRIPT_SESSION_CREATE
        ackNext(trialFuture, Map.of("status", "VALIDATED", "hitCount", 0));  // SCRIPT_SESSION_VALIDATE
        ackNext(trialFuture, Map.of("status", "APPLIED", "hitCount", 0));    // SCRIPT_SESSION_APPLY
        Map<String, Object> trialResult = trialFuture.get(20, TimeUnit.SECONDS);
        assertThat(trialResult.get("status")).isEqualTo("APPLIED");

        // revert dispatches SCRIPT_SESSION_REVERT; ack it.
        var revertFuture = CompletableFuture.supplyAsync(() -> sessionService.revert(admin, session.sessionId()));
        ackNext(revertFuture, Map.of("status", "REVERTED", "hitCount", 0));
        var reverted = revertFuture.get(20, TimeUnit.SECONDS);
        assertThat(reverted.status()).isEqualTo(AutomationSessionStatus.REVERTED);
        assertThat(reverted.cleanupResult().get("revertedCount")).as("revert should undo the trial resource").isNotEqualTo(0);
    }

    private void ackNext(CompletableFuture<?> future, Map<String, Object> ackResult) {
        Map<String, Object> polled = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> candidate = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 10));
            if (!"NO_COMMAND".equals(candidate.get("status"))) {
                polled = candidate;
                break;
            }
            try { Thread.sleep(25); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        if (polled == null) {
            // Surface the cause: if the async call already failed, report its error.
            String cause = "no command dispatched";
            if (future.isDone()) {
                try { future.get(); }
                catch (Exception e) { cause = "async call failed: " + e.getCause(); }
            }
            Integer cmdCount = jdbc.queryForObject(
                    "select count(*) from agent_command where agent_id = ?", Integer.class, agentId);
            cause += " (agent_command count=" + cmdCount + ", futureDone=" + future.isDone() + ")";
            throw new AssertionError("agent command was not dispatched within timeout: " + cause);
        }
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", "ACKED");
        ack.put("result", ackResult);
        commands.ack(String.valueOf(polled.get("id")), agentCtx, ack);
    }
}
