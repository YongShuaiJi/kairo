package com.example.kairo.platform;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.platform.automation.AutomationSessionService;
import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;8 / &sect;9.6: one non-stitched, token-plus-intent AI end-to-end that drives
 * the entire automation-session lifecycle. The token-gated entry (create session from
 * intent, resolve structured targets, validate a broken script, observe the operation
 * stream, one-click revert) runs through the real HTTP surface with a scoped AI bearer
 * token; the agent-channel steps (preview target resolution, real-JVM trial, promote)
 * run through the real {@link AutomationSessionService} on the real agent command channel
 * (script compile + dispatch + ack). The flow is: create -&gt; resolve -&gt; validate broken
 * (structured diagnostics) -&gt; AI auto-corrects on the diagnostic code -&gt; re-validate
 * (valid) -&gt; preview -&gt; trial (create+validate+apply, simulated agent acks) -&gt; observe -&gt; promote
 * -&gt; one-click revert. Nothing is mocked at the service boundary; only the agent acks
 * are simulated (no live agent process, so no real method-behavior change is asserted here),
 * mirroring {@link V16AiTrialRealJvmTest}. The real-agent, real-instrumentation proof of the
 * trial's behavior change + revert lives in
 * {@code com.example.kairo.agent.server.V16RealAgentAiLifecycleTest}.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_ai_full_e2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "kairo.platform.auth.mode=local-token",
        "kairo.platform.auth.bootstrap-token=bootstrap-test-token",
        "kairo.platform.rollout.scheduler.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V16AiFullLifecycleE2eTest {

    @Autowired MockMvc mockMvc;
    @Autowired AgentCommandService commands;
    @Autowired AutomationSessionService sessionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final AtomicLong COUNTER = new AtomicLong();

    private String instanceId;
    private String agentId;
    private String aiToken;
    private RequestContext aiCtx;
    private RequestContext agentCtx;

    @BeforeEach
    void setUp() throws Exception {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        long n = COUNTER.incrementAndGet();
        instanceId = "instance-ai-full-" + n;
        agentId = "agent-ai-full-" + n;
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, created_at, updated_at)
                values (?, 'app-default', 'env-dev', 'ai-full', 'localhost', '1', 'java',
                  'ACTIVE', '{}', current_timestamp, current_timestamp)
                """, instanceId);
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values (?, ?, 'ACTIVE', 'test', 'test', '127.0.0.1', 1, 'hash', '[]',
                  current_timestamp, current_timestamp)
                """, agentId, instanceId);
        aiCtx = new RequestContext("ai-e2e", "corr-ai-full", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr-ai-full", "127.0.0.1", "agent", "test");

        // Token + intent: issue an AI-scoped token (RULE_MANAGE) the whole flow will use.
        aiToken = mapper.readTree(mockMvc.perform(post("/api/v1/auth/tokens")
                        .header("Authorization", "Bearer bootstrap-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"ai-e2e","ttlSeconds":3600,"scope":["RULE_MANAGE"],"source":"mcp"}
                                """))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    @AfterEach
    void tearDown() {
        jdbc.update("delete from operation_event where operation_id in (select id from operation where automation_session_id in (select id from automation_session where caller = 'ai-e2e-bot'))");
        jdbc.update("delete from operation where automation_session_id in (select id from automation_session where caller = 'ai-e2e-bot')");
        jdbc.update("delete from automation_session_resource where session_id in (select id from automation_session where caller = 'ai-e2e-bot')");
        jdbc.update("delete from automation_session where caller = 'ai-e2e-bot'");
        jdbc.update("delete from script_session_event where session_id in (select id from script_session where agent_id = ?)", agentId);
        jdbc.update("delete from script_session where agent_id = ?", agentId);
        jdbc.update("delete from agent_command where agent_id = ?", agentId);
        jdbc.update("delete from agent_instance where id = ?", agentId);
        jdbc.update("delete from instance where id = ?", instanceId);
    }

    @Test
    void aiFullLifecycleFromTokenAndIntentToRevert() throws Exception {
        // 1. Create the automation session from intent (token-gated; AI narrows to SAFE).
        String sessionId = mapper.readTree(mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("Authorization", "Bearer " + aiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"caller":"ai-e2e-bot","source":"mcp","applicationId":"app-default",
                                 "environmentId":"env-dev","agentId":"%s",
                                 "requestedCapabilityProfile":"SAFE","ttlMillis":600000}
                                """.formatted(agentId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxCapabilityProfile").value("SAFE"))
                .andReturn().getResponse().getContentAsString()).get("sessionId").asText();

        // 2. resolve-targets returns a compact, structured context bundle (the AI never sees raw class dumps).
        mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/resolve-targets")
                        .header("Authorization", "Bearer " + aiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"pay\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scriptApiSurface.schema.properties").exists())
                .andExpect(jsonPath("$.scriptApiSurface.examples").isArray())
                .andExpect(jsonPath("$.sizeBytes").isNumber());

        // resolve-targets fans out a DISCOVER_TARGETS command per agent and does not await it
        // past its own deadline; drain any it left pending so later agent-ack steps see only
        // their own commands.
        drainPendingCommands();

        // 3. Validate a deliberately broken script -> structured diagnostics (branch on code, not message).
        mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/validate-script")
                        .header("Authorization", "Bearer " + aiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"script\":\"def x =\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").exists())
                .andExpect(jsonPath("$.diagnostics[0].severity").exists());

        // 4. AI auto-corrects on the diagnostic code -> re-validate (valid).
        mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/validate-script")
                        .header("Authorization", "Bearer " + aiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"script\":\"ctx.result = 1\\n\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true));

        Map<String, Object> resolutionTarget = new LinkedHashMap<>();
        resolutionTarget.put("className", "com.example.PaymentService");
        resolutionTarget.put("methodName", "pay");
        Map<String, Object> matcher = new LinkedHashMap<>();
        matcher.put("classLoaderId", "bootstrap");
        matcher.put("descriptor", "()V");
        resolutionTarget.put("matcher", matcher);
        resolutionTarget.put("location", "METHOD_ENTER");

        // 5. Preview: target resolution on the real agent channel (ack MATCHED).
        var preview = runWithAcks(() -> sessionService.preview(aiCtx, sessionId,
                        new AutomationSessionService.PreviewRequest(resolutionTarget)),
                Map.of("status", "MATCHED", "matchedCount", 1, "risk", "LOW"));
        assertThat(preview.previewToken()).startsWith("prev-");
        assertThat(preview.revision()).isPositive();

        // 6. Real-JVM trial: create + validate + apply through the agent command channel.
        Map<String, Object> trialTarget = new LinkedHashMap<>();
        trialTarget.put("className", "com.example.PaymentService");
        trialTarget.put("methodName", "pay");
        trialTarget.put("classLoaderId", "bootstrap");
        trialTarget.put("methodDescriptor", "()V");
        Map<String, Object> trial = runWithAcks(() -> sessionService.trial(aiCtx, sessionId,
                        new AutomationSessionService.TrialRequest(trialTarget, "ctx.result = 1\n",
                                CapabilityProfile.SAFE, 60_000L, 1L)),
                Map.of("status", "CREATED", "hitCount", 0),
                Map.of("status", "VALIDATED", "hitCount", 0),
                Map.of("status", "APPLIED", "hitCount", 0));
        assertThat(trial.get("status")).isEqualTo("APPLIED");
        String scriptSessionId = String.valueOf(trial.get("sessionId"));

        // 7. Observe: the session event stream + the unified Operation resource record the trial.
        mockMvc.perform(get("/api/v1/automation-sessions/" + sessionId + "/events")
                        .header("Authorization", "Bearer " + aiToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='operation')].operationType",
                        org.hamcrest.Matchers.hasItem("AUTOMATION_TRIAL")));
        mockMvc.perform(get("/api/v1/operations?resourceType=script-session&resourceId=" + scriptSessionId)
                        .header("Authorization", "Bearer " + aiToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("AUTOMATION_TRIAL"));

        // 8. Promote the trial to a formal rule (ack PROMOTE).
        Map<String, Object> promoted = runWithAcks(() -> sessionService.promote(aiCtx, sessionId, scriptSessionId),
                Map.of("status", "REVERTED", "hitCount", 0));
        assertThat(String.valueOf(promoted.get("formalRuleId"))).isNotBlank();

        // 9. One-click revert (token-gated HTTP): reliably undoes the session's reversible resources.
        mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/revert")
                        .header("Authorization", "Bearer " + aiToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERTED"))
                .andExpect(jsonPath("$.cleanupResult.revertedCount").isNumber());
    }

    /** Run a service call that dispatches agent commands, acknowledging each in order. */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    private <T> T runWithAcks(Supplier<T> call, Map<String, Object>... ackResults) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(call::get);
        for (Map<String, Object> ack : ackResults) {
            ackNext(future, ack);
        }
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) throw re;
            throw new AssertionError(e);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Drain commands left pending by fire-and-forget fan-out (e.g. DISCOVER_TARGETS). */
    private void drainPendingCommands() {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> candidate = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 10));
            if ("NO_COMMAND".equals(candidate.get("status"))) break;
            Map<String, Object> ack = new LinkedHashMap<>();
            ack.put("status", "ACKED");
            ack.put("result", Map.of());
            commands.ack(String.valueOf(candidate.get("id")), agentCtx, ack);
        }
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
            Integer cmdCount = jdbc.queryForObject(
                    "select count(*) from agent_command where agent_id = ?", Integer.class, agentId);
            String cause = "no command dispatched (agent_command count=" + cmdCount + ")";
            if (future.isDone()) {
                try { future.get(); } catch (Exception e) { cause = "async call failed: " + e.getCause(); }
            }
            throw new AssertionError("agent command was not dispatched within timeout: " + cause);
        }
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", "ACKED");
        ack.put("result", ackResult);
        commands.ack(String.valueOf(polled.get("id")), agentCtx, ack);
    }
}
