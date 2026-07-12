package com.example.kairo.platform.script;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.ScriptSessionResult;
import com.example.kairo.api.ScriptSessionStatus;
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

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class ScriptSessionLifecycleIntegrationTest {

    @Autowired ScriptSessionService service;
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
        instanceId = "instance-script-" + n;
        agentId = "agent-script-" + n;
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, created_at, updated_at)
                values (?, 'app-default', 'env-dev', 'script', 'localhost', '1', 'java',
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
        jdbc.update("delete from script_session_event where session_id in (select id from script_session where agent_id = ?)", agentId);
        jdbc.update("delete from script_session where agent_id = ?", agentId);
        jdbc.update("delete from agent_command where agent_id = ?", agentId);
        jdbc.update("delete from script_capability_policy where scope = 'APPLICATION'");
        jdbc.update("delete from agent_instance where id = ?", agentId);
        jdbc.update("delete from instance where id = ?", instanceId);
    }

    @Test
    void fullLifecycleCreateValidateApplyPromoteRevert() {
        String sessionId = createSession("return 42", CapabilityProfile.SAFE,
                Map.of("status", "CREATED", "hitCount", 0));
        assertThat(sessionId).isNotBlank();

        // validate: CREATED -> VALIDATED
        ScriptSessionResult validated = runWithAck(() -> service.validate(admin, sessionId),
                Map.of("status", "VALIDATED", "hitCount", 0));
        assertThat(validated.status()).isEqualTo(ScriptSessionStatus.VALIDATED);

        // apply: VALIDATED -> APPLIED
        ScriptSessionResult applied = runWithAck(() -> service.apply(admin, sessionId),
                Map.of("status", "APPLIED", "hitCount", 0));
        assertThat(applied.status()).isEqualTo(ScriptSessionStatus.APPLIED);

        // promote: APPLIED -> REVERTED (formal rule under same id)
        ScriptSessionResult promoted = runWithAck(() -> service.promote(admin, sessionId),
                Map.of("status", "REVERTED", "hitCount", 0));
        assertThat(promoted.status()).isEqualTo(ScriptSessionStatus.REVERTED);
        assertThat(jdbc.queryForObject("select formal_rule_id from script_session where id = ?",
                String.class, sessionId)).isEqualTo(sessionId);

        // revert is idempotent on a terminal session
        ScriptSessionResult reverted = service.revert(admin, sessionId);
        assertThat(reverted.status()).isEqualTo(ScriptSessionStatus.REVERTED);

        // the event history records every transition
        List<ScriptSessionEvent> events = service.history(admin, sessionId);
        assertThat(events).isNotEmpty();
        assertThat(events.stream().map(ScriptSessionEvent::toStatus).toList())
                .contains("CREATED", "VALIDATED", "APPLIED", "REVERTED");
    }

    @Test
    void createdSessionRecordsEffectiveTierAndHashOnly() {
        // Tighten the app ceiling so the effective tier clamps the requested UNRESTRICTED down to EXTENDED.
        jdbc.update("""
                insert into script_capability_policy(scope, application_id, allowed_max_profile, revision,
                  policy_hash, modified_by, created_at, updated_at)
                values ('APPLICATION', 'app-default', 'EXTENDED', 1, 'app-hash', 'system',
                  current_timestamp, current_timestamp)
                """);
        String sessionId = createSession("return 99", CapabilityProfile.UNRESTRICTED,
                Map.of("status", "CREATED", "hitCount", 0));
        Map<String, Object> row = jdbc.queryForMap(
                "select requested_profile, effective_profile, platform_max_profile, application_max_profile, "
                        + "policy_revision, policy_hash, script_hash from script_session where id = ?", sessionId);
        assertThat(row.get("REQUESTED_PROFILE")).isEqualTo("UNRESTRICTED");
        assertThat(row.get("EFFECTIVE_PROFILE")).isEqualTo("EXTENDED");
        assertThat(row.get("PLATFORM_MAX_PROFILE")).isEqualTo("UNRESTRICTED");
        assertThat(row.get("APPLICATION_MAX_PROFILE")).isEqualTo("EXTENDED");
        assertThat(((Number) row.get("POLICY_REVISION")).longValue()).isEqualTo(1L);
        assertThat(row.get("SCRIPT_HASH")).isNotNull();

        // The script source is never persisted in the command payload (only the hash is).
        String payload = jdbc.queryForObject(
                "select payload_json from agent_command where agent_id = ? and command_type = 'SCRIPT_SESSION_CREATE'",
                String.class, agentId);
        assertThat(payload).contains("scriptHash").doesNotContain("return 99");
    }

    @Test
    void validateRejectsWrongStateWithConflict() {
        String sessionId = createSession("return 1", CapabilityProfile.SAFE,
                Map.of("status", "CREATED", "hitCount", 0));
        runWithAck(() -> service.validate(admin, sessionId),
                Map.of("status", "VALIDATED", "hitCount", 0));
        assertThatThrownBy(() -> service.validate(admin, sessionId))
                .isInstanceOfSatisfying(com.example.kairo.platform.service.PlatformException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void applyRequiresValidatedState() {
        String sessionId = createSession("return 1", CapabilityProfile.SAFE,
                Map.of("status", "CREATED", "hitCount", 0));
        assertThatThrownBy(() -> service.apply(admin, sessionId))
                .isInstanceOfSatisfying(com.example.kairo.platform.service.PlatformException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void promoteRequiresValidatedOrApplied() {
        String sessionId = createSession("return 1", CapabilityProfile.SAFE,
                Map.of("status", "CREATED", "hitCount", 0));
        assertThatThrownBy(() -> service.promote(admin, sessionId))
                .isInstanceOfSatisfying(com.example.kairo.platform.service.PlatformException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void describeUnknownSessionReturns404() {
        assertThatThrownBy(() -> service.describe(admin, "no-such-session"))
                .isInstanceOfSatisfying(com.example.kairo.platform.service.PlatformException.class,
                        e -> assertThat(e.status()).isEqualTo(404));
    }

    @Test
    void createIsIdempotentByIdempotencyKey() {
        String key = "idem-" + UUID.randomUUID();
        Map<String, Object> request = baseRequest("return 1", CapabilityProfile.SAFE);
        request.put("idempotencyKey", key);
        ScriptSessionResult first = runWithAck(() -> service.create(admin, request),
                Map.of("status", "CREATED", "hitCount", 0));
        // Second call with the same key returns the existing session without dispatching a new command.
        ScriptSessionResult second = service.create(admin, request);
        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(commandsCount()).isEqualTo(1);
    }

    @Test
    void createRejectsSecondActiveSessionForSameTarget() {
        createSession("return 1", CapabilityProfile.SAFE,
                Map.of("status", "CREATED", "hitCount", 0));
        Map<String, Object> request = baseRequest("return 2", CapabilityProfile.SAFE);
        request.put("sessionId", "session-second-" + COUNTER.incrementAndGet());
        assertThatThrownBy(() -> service.create(admin, request))
                .isInstanceOfSatisfying(com.example.kairo.platform.service.PlatformException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void createRejectsTtlAndHitsAbovePlatformLimit() {
        Map<String, Object> request = baseRequest("return 1", CapabilityProfile.SAFE);
        request.put("ttlMillis", 10_000_000L);
        assertThatThrownBy(() -> service.create(admin, request))
                .isInstanceOfSatisfying(com.example.kairo.platform.service.PlatformException.class,
                        e -> assertThat(e.status()).isEqualTo(400));
    }

    @Test
    void agentFailureMarksSessionFailedWithDiagnostic() {
        String sessionId = createSession("return 1", CapabilityProfile.SAFE,
                Map.of("status", "CREATED", "hitCount", 0));
        // The agent acks FAILED for validate with a diagnostic.
        Map<String, Object> diagnostic = Map.of(
                "phase", "COMPILATION", "severity", "ERROR", "line", 3, "column", 1,
                "code", "SCRIPT_COMPILE_ERROR", "message", "unexpected token",
                "targetClassLoaderId", "loader-1", "suggestion", "fix the syntax");
        ScriptSessionResult failed = runWithAck(() -> service.validate(admin, sessionId),
                Map.of("status", "FAILED", "hitCount", 0, "diagnostics", List.of(diagnostic)),
                "FAILED", "compile error");
        assertThat(failed.status()).isEqualTo(ScriptSessionStatus.FAILED);
        assertThat(failed.diagnostics()).hasSize(1);
        assertThat(failed.diagnostics().get(0).code()).isEqualTo("SCRIPT_COMPILE_ERROR");
        // A failed session cannot be retried: validate now conflicts.
        assertThatThrownBy(() -> service.validate(admin, sessionId))
                .isInstanceOfSatisfying(com.example.kairo.platform.service.PlatformException.class,
                        e -> assertThat(e.status()).isEqualTo(409));
    }

    @Test
    void revertOfNonAppliedSessionDispatchesRevertAndMarksReverted() {
        String sessionId = createSession("return 1", CapabilityProfile.SAFE,
                Map.of("status", "CREATED", "hitCount", 0));
        ScriptSessionResult reverted = runWithAck(() -> service.revert(admin, sessionId),
                Map.of("status", "REVERTED", "hitCount", 0));
        assertThat(reverted.status()).isEqualTo(ScriptSessionStatus.REVERTED);
    }

    @Test
    void expirySweepMarksOverdueSessionsExpired() {
        // Insert a session already past its deadline directly.
        String sessionId = "session-expired-" + COUNTER.incrementAndGet();
        long now = System.currentTimeMillis();
        Timestamp created = new Timestamp(now - 3_600_000L);
        Timestamp expires = new Timestamp(now - 1_800_000L);
        Timestamp applied = new Timestamp(now - 2_400_000L);
        jdbc.update("""
                insert into script_session(id, agent_id, application_id, target_class_name, target_class_loader_id,
                  target_method_name, target_method_descriptor, script_hash, requested_profile, effective_profile,
                  platform_max_profile, application_max_profile, policy_revision, policy_hash, ttl_millis, max_hits,
                  status, hit_count, version, idempotency_key, requested_by, formal_rule_id, agent_result_json,
                  diagnostics_json, created_at, expires_at, applied_at, reverted_at, updated_at, created_by,
                  correlation_id)
                values (?, ?, 'app-default', 'com.example.Target', 'loader-1', 'call', '()V', 'hash',
                  'SAFE', 'SAFE', 'UNRESTRICTED', 'UNRESTRICTED', 0, 'h', 60000, 1, 'APPLIED', 0, 1, ?,
                  'system', null, '{}', '[]', ?, ?, ?, null, ?, 'system', '')
                """, sessionId, agentId, "idem-expired-" + sessionId, created, expires, applied,
                new Timestamp(now));
        Map<String, Object> result = service.expireSessions();
        assertThat(result.get("expired")).isEqualTo(1);
        String status = jdbc.queryForObject("select status from script_session where id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("EXPIRED");
        // A best-effort revert command was enqueued for the previously-applied session.
        Integer revertCommands = jdbc.queryForObject(
                "select count(*) from agent_command where agent_id = ? and command_type = 'SCRIPT_SESSION_REVERT'",
                Integer.class, agentId);
        assertThat(revertCommands).isGreaterThanOrEqualTo(1);
    }

    @Test
    void expirySweepToleratesMissingAgentAndStillMarksExpired() {
        // Insert an overdue session, then delete the agent so the best-effort revert cannot dispatch.
        String sessionId = "session-expired-gone-" + COUNTER.incrementAndGet();
        long now = System.currentTimeMillis();
        jdbc.update("""
                insert into script_session(id, agent_id, application_id, target_class_name, target_class_loader_id,
                  target_method_name, target_method_descriptor, script_hash, requested_profile, effective_profile,
                  platform_max_profile, application_max_profile, policy_revision, policy_hash, ttl_millis, max_hits,
                  status, hit_count, version, idempotency_key, requested_by, formal_rule_id, agent_result_json,
                  diagnostics_json, created_at, expires_at, applied_at, reverted_at, updated_at, created_by,
                  correlation_id)
                values (?, ?, 'app-default', 'com.example.Target', 'loader-1', 'call', '()V', 'hash',
                  'SAFE', 'SAFE', 'UNRESTRICTED', 'UNRESTRICTED', 0, 'h', 60000, 1, 'APPLIED', 0, 1, ?,
                  'system', null, '{}', '[]', ?, ?, ?, null, ?, 'system', '')
                """, sessionId, agentId, "idem-gone-" + sessionId,
                new Timestamp(now - 3_600_000L), new Timestamp(now - 1_800_000L),
                new Timestamp(now - 2_400_000L), new Timestamp(now));
        jdbc.update("delete from agent_instance where id = ?", agentId);

        Map<String, Object> result = service.expireSessions();
        assertThat(result.get("expired")).isEqualTo(1);
        String status = jdbc.queryForObject("select status from script_session where id = ?", String.class, sessionId);
        assertThat(status).isEqualTo("EXPIRED");

        // Re-add the agent so @AfterEach cleanup can delete the instance rows cleanly.
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values (?, ?, 'ACTIVE', 'test', 'test', '127.0.0.1', 1, 'hash', '[]',
                  current_timestamp, current_timestamp)
                """, agentId, instanceId);
    }

    @Test
    void compileDispatchesCommandAndReturnsAgentResult() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("agentId", agentId);
        request.put("script", "return 7");
        request.put("targetClassLoaderId", "loader-1");
        request.put("capabilityProfile", "SAFE");
        var result = runWithAck(() -> service.compile(admin, request),
                Map.of("successful", true, "compilerVersion", "groovy-4.0",
                        "diagnostics", List.of(), "targetClassLoaderId", "loader-1"));
        assertThat(result.successful()).isTrue();
        assertThat(result.compilerVersion()).isEqualTo("groovy-4.0");
        assertThat(result.targetClassLoaderId()).isEqualTo("loader-1");
        // The compile command payload carries the hash but not the source.
        String payload = jdbc.queryForObject(
                "select payload_json from agent_command where agent_id = ? and command_type = 'SCRIPT_COMPILE'",
                String.class, agentId);
        assertThat(payload).doesNotContain("return 7");
    }

    // ------------------------------------------------------------------ helpers

    private String createSession(String script, CapabilityProfile profile, Map<String, Object> ackResult) {
        Map<String, Object> request = baseRequest(script, profile);
        return runWithAck(() -> service.create(admin, request), ackResult).sessionId();
    }

    private Map<String, Object> baseRequest(String script, CapabilityProfile profile) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("agentId", agentId);
        request.put("target", Map.of("className", "com.example.Target",
                "classLoaderId", "loader-1", "methodName", "call", "methodDescriptor", "()V"));
        request.put("script", script);
        request.put("capabilityProfile", profile.name());
        request.put("ttlMillis", 60000);
        request.put("maxHits", 1);
        return request;
    }

    private <T> T runWithAck(Supplier<T> call, Map<String, Object> ackResult) {
        return runWithAck(call, ackResult, "ACKED", null);
    }

    private <T> T runWithAck(Supplier<T> call, Map<String, Object> ackResult,
                             String ackStatus, String errorMessage) {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(call::get);
        simulateAgentAck(ackResult, ackStatus, errorMessage);
        try {
            return future.get(20, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new AssertionError(e);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void simulateAgentAck(Map<String, Object> ackResult, String ackStatus, String errorMessage) {
        Map<String, Object> polled = null;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> candidate = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 10));
            if (!"NO_COMMAND".equals(candidate.get("status"))) {
                polled = candidate;
                break;
            }
            sleepQuiet(25);
        }
        assertThat(polled).as("agent command was not dispatched within timeout").isNotNull();
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", ackStatus);
        ack.put("result", ackResult);
        if (errorMessage != null) {
            ack.put("errorMessage", errorMessage);
        }
        commands.ack(String.valueOf(polled.get("id")), agentCtx, ack);
    }

    private int commandsCount() {
        Integer count = jdbc.queryForObject("select count(*) from agent_command where agent_id = ?", Integer.class, agentId);
        return count == null ? 0 : count;
    }

    private static void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
