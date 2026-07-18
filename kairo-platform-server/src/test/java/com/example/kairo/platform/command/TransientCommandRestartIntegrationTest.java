package com.example.kairo.platform.command;

import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.7 M1-B &sect;8.2 (TRANSIENT): on a Platform restart against the same persistent database,
 * orphan TRANSIENT commands (PENDING or DISPATCHED) enter the fixed failure state
 * {@code TRANSIENT_COMMAND_CONTEXT_LOST} with minimal diagnostics, the recovery is idempotent
 * across startups, and sensitive transient material (script source, class bytes, tokens) is
 * never persisted or logged by the recovery.
 *
 * <p>Each test launches real {@link com.example.kairo.platform.KairoPlatformApplication} contexts
 * against a per-method file-backed H2. No sleeps; lease expiry is deterministic via a fixed past
 * timestamp.
 */
class TransientCommandRestartIntegrationTest {

    /** Every TRANSIENT command family (&sect;4.2): 6 explicit + 5 BYTECODE_* + 6 SCRIPT_*. */
    private static final List<String> TRANSIENT_TYPES = List.of(
            "START_RECORDING", "STOP_RECORDING",
            "DISCOVER_TARGETS", "LIST_LOADERS", "LIST_CALL_SITES", "RESOLVE_TARGET",
            "BYTECODE_TRANSFORMATIONS", "BYTECODE_GET", "BYTECODE_PREVIEW",
            "BYTECODE_CAPTURE", "BYTECODE_DIFF",
            "SCRIPT_SESSION_CREATE", "SCRIPT_SESSION_VALIDATE", "SCRIPT_SESSION_APPLY",
            "SCRIPT_SESSION_PROMOTE", "SCRIPT_SESSION_REVERT", "SCRIPT_COMPILE");

    @TempDir
    Path tempDir;

    private RestartRecoveryHarness harness;

    @BeforeEach
    void setUp() {
        harness = new RestartRecoveryHarness(tempDir);
    }

    @AfterEach
    void tearDown() {
        harness.stop();
    }

    @Test
    void pendingTransientOrphansFailWithContextLostCodeAfterRestart() {
        harness.start();
        String agentId = seed();
        for (String type : TRANSIENT_TYPES) {
            createManual(agentId, type, 1);
        }
        // Sanity: all PENDING before the restart, none failed yet.
        assertThat(countByStatus("PENDING")).isEqualTo(TRANSIENT_TYPES.size());
        assertThat(countByStatus("FAILED")).isZero();
        assertThat(recoveryAuditCount()).isZero();
        harness.stop();

        // §8.2#5: every orphan TRANSIENT PENDING command -> FAILED with the fixed code.
        harness.start();
        assertThat(countByStatus("FAILED")).isEqualTo(TRANSIENT_TYPES.size());
        assertThat(countByStatus("PENDING")).isZero();
        assertThat(countByStatus("DISPATCHED")).isZero();
        assertThat(countFailedWithContextLostCode()).isEqualTo(TRANSIENT_TYPES.size());
        // Minimal diagnostics: one recovery audit event per recovered command.
        assertThat(recoveryAuditCount()).isEqualTo(TRANSIENT_TYPES.size());
    }

    @Test
    void dispatchedTransientOrphanFailsWithContextLostCodeAfterRestart() {
        harness.start();
        String agentId = seed();
        String id = createManual(agentId, "DISCOVER_TARGETS", 1);
        // A live-lease DISPATCHED transient: the platform crashes while the agent is mid-flight.
        // The in-memory waiter/exchange is lost on restart regardless of the lease.
        Map<String, Object> polled = poll(agentId);
        assertThat(polled.get("status")).isEqualTo("DISPATCHED");
        assertThat(((Number) polled.get("attempts")).longValue()).isEqualTo(1L);
        // Leave the lease LIVE (not expired) -- the defining loss is the in-memory context, not
        // the lease, so a live-lease DISPATCHED transient must still fail on restart.
        harness.stop();

        harness.start();
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(errorMessage(id)).isEqualTo(AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE);
        // §8.2#5: audit retained with minimal diagnostics.
        assertThat(recoveryAuditCountForCommand(id)).isEqualTo(1L);
    }

    @Test
    void transientRecoveryLeavesNoSensitiveMaterial() {
        // §8.2#7 / §4.2: script source, class bytes and tokens are in-memory only; the recovery
        // must never persist or log them. Canary strings prove the isolation end-to-end.
        String scriptCanary = "CANARY-SCRIPT-SOURCE-" + UUID.randomUUID();
        String bytecodeCanary = "CANARY-BYTECODE-" + UUID.randomUUID();
        String tokenCanary = "CANARY-TOKEN-" + UUID.randomUUID();

        harness.start();
        String agentId = seed();
        RequestContext admin = RestartRecoveryHarness.admin();

        // SCRIPT_*: scriptSource is registered in the in-memory exchange only; the durable row
        // stores just the script hash. The canary must never reach the database.
        Map<String, Object> scriptPayload = new LinkedHashMap<>();
        scriptPayload.put("commandType", "SCRIPT_COMPILE");
        scriptPayload.put("scriptHash", "0123456789abcdef");
        harness.commands().createScriptCommand(admin, agentId, "SCRIPT_COMPILE",
                scriptPayload, scriptCanary, "idem-script-" + UUID.randomUUID());

        // BYTECODE_*: the transient class bytes are registered in the in-memory exchange only.
        Map<String, Object> bytecodePayload = new LinkedHashMap<>();
        bytecodePayload.put("commandType", "BYTECODE_GET");
        bytecodePayload.put("classId", "com.example.Service");
        harness.commands().createBytecodeDiagnosticCommand(admin, agentId, "BYTECODE_GET",
                bytecodePayload, bytecodeCanary.getBytes(StandardCharsets.UTF_8));

        // A TRANSIENT command whose caller-supplied payload carries a token-like field. The token
        // is in payload_json (the caller put it there), but the recovery itself must never echo it
        // into error_message or the recovery audit.
        Map<String, Object> tokenPayload = new LinkedHashMap<>();
        tokenPayload.put("commandType", "DISCOVER_TARGETS");
        tokenPayload.put("authorization", tokenCanary);
        harness.commands().createManualCommand(admin, agentId, tokenPayload);

        // The in-memory canaries exist in ctx1's exchanges (proving the test is meaningful).
        assertThat(harness.jdbc().queryForObject(
                "select count(*) from agent_command where command_type in ('SCRIPT_COMPILE','BYTECODE_GET','DISCOVER_TARGETS')",
                Long.class)).isEqualTo(3L);
        harness.stop();

        // Restart: fresh exchanges (empty); recovery fails the three orphans from the DB row only.
        harness.start();
        assertThat(countFailedWithContextLostCode()).isEqualTo(3);
        // Script source canary: absent from every agent_command text column and every audit column.
        assertThat(canaryInAgentCommand(scriptCanary))
                .as("script source must never be persisted").isZero();
        assertThat(canaryInAnyAudit(scriptCanary))
                .as("script source must never be logged").isZero();
        // Class-bytes canary: absent everywhere.
        assertThat(canaryInAgentCommand(bytecodeCanary))
                .as("class bytes must never be persisted").isZero();
        assertThat(canaryInAnyAudit(bytecodeCanary))
                .as("class bytes must never be logged").isZero();
        // Token canary: may live in payload_json (caller input) but must NOT appear in
        // error_message or in the recovery audit evidence.
        assertThat(canaryInRecoveryEvidence(tokenCanary))
                .as("recovery must never log tokens/authorization").isZero();
        assertThat(canaryInCommandErrorOrAudit(tokenCanary))
                .as("recovery error_message/audit must never echo tokens").isZero();
    }

    @Test
    void transientRecoveryIsIdempotentAcrossStartups() {
        // §8.2#6: a second startup does not mutate already-recovered rows or duplicate recovery
        // audit evidence. Three real startups against the same H2 file.
        harness.start();
        String agentId = seed();
        String id = createManual(agentId, "LIST_LOADERS", 1);
        assertThat(recoveryAuditCountForCommand(id)).isZero();
        harness.stop();

        // First restart: recovery fails the orphan once.
        harness.start();
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(errorMessage(id)).isEqualTo(AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE);
        assertThat(recoveryAuditCountForCommand(id)).isEqualTo(1L);
        java.sql.Timestamp completedAtFirst = completedAt(id);
        java.sql.Timestamp updatedAtFirst = updatedAt(id);
        long totalRecoveryAuditsFirst = recoveryAuditCount();
        harness.stop();

        // Second restart: no-op on the already-FAILED row -- no mutation, no duplicate audit.
        harness.start();
        assertThat(status(id)).isEqualTo("FAILED");
        assertThat(errorMessage(id)).isEqualTo(AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE);
        assertThat(completedAt(id)).isEqualTo(completedAtFirst);
        assertThat(updatedAt(id)).isEqualTo(updatedAtFirst);
        assertThat(recoveryAuditCountForCommand(id))
                .as("no duplicate per-command recovery audit").isEqualTo(1L);
        assertThat(recoveryAuditCount())
                .as("no new recovery audit rows on the second startup")
                .isEqualTo(totalRecoveryAuditsFirst);

        // A direct re-run of the recovery method is also a no-op (still no duplicate).
        CommandStartupRecoveryService.RecoveryResult rerun =
                harness.recovery().recoverOrphanTransientCommands();
        assertThat(rerun.recovered()).isZero();
        assertThat(recoveryAuditCountForCommand(id)).isEqualTo(1L);
    }

    @Test
    void currentProcessTransientCommandIsNotMisclassifiedAsAnOrphan() {
        // ApplicationRunner executes after the embedded server has started. A command created by
        // this new process can therefore race with a slow startup scan and still has a valid
        // in-memory context. Re-running recovery must use the captured startup boundary and leave
        // that current-process command untouched.
        harness.start();
        String agentId = seed();
        String id = createManual(agentId, "LIST_LOADERS", 1);

        CommandStartupRecoveryService.RecoveryResult rerun =
                harness.recovery().recoverOrphanTransientCommands();

        assertThat(rerun.recovered()).isZero();
        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(errorMessage(id)).isNull();
        assertThat(recoveryAuditCountForCommand(id)).isZero();
    }

    // -------------------------------------------------------- helpers

    private String seed() {
        String agentId = "agent-transient-" + UUID.randomUUID();
        String instanceId = "inst-transient-" + UUID.randomUUID();
        harness.seedRuntime(agentId, instanceId);
        return agentId;
    }

    private String createManual(String agentId, String type, long maxAttempts) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", type);
        request.put("maxAttempts", maxAttempts);
        return String.valueOf(harness.commands()
                .createManualCommand(RestartRecoveryHarness.admin(), agentId, request).get("id"));
    }

    private Map<String, Object> poll(String agentId) {
        return harness.commands().pollNext(agentId, RestartRecoveryHarness.agentContext(agentId),
                Map.of("leaseSeconds", 60));
    }

    private String status(String id) {
        return harness.jdbc().queryForObject("select status from agent_command where id = ?",
                String.class, id);
    }

    private String errorMessage(String id) {
        return harness.jdbc().queryForObject("select error_message from agent_command where id = ?",
                String.class, id);
    }

    private java.sql.Timestamp completedAt(String id) {
        return harness.jdbc().queryForObject("select completed_at from agent_command where id = ?",
                java.sql.Timestamp.class, id);
    }

    private java.sql.Timestamp updatedAt(String id) {
        return harness.jdbc().queryForObject("select updated_at from agent_command where id = ?",
                java.sql.Timestamp.class, id);
    }

    private long countByStatus(String status) {
        return harness.jdbc().queryForObject(
                "select count(*) from agent_command where status = ?", Long.class, status);
    }

    private long countFailedWithContextLostCode() {
        return harness.jdbc().queryForObject(
                "select count(*) from agent_command where status = 'FAILED' "
                        + "and error_message = ?", Long.class,
                AgentCommandClassification.TRANSIENT_CONTEXT_LOST_CODE);
    }

    private long recoveryAuditCount() {
        return harness.jdbc().queryForObject(
                "select count(*) from audit_record where action = 'agent_command.recover_transient'",
                Long.class);
    }

    private long recoveryAuditCountForCommand(String commandId) {
        return harness.jdbc().queryForObject(
                "select count(*) from audit_record where action = 'agent_command.recover_transient' "
                        + "and resource_id = ?", Long.class, commandId);
    }

    /** Canary occurrences in any agent_command text column (payload/result/error). */
    private long canaryInAgentCommand(String canary) {
        String pattern = "%" + canary.toLowerCase() + "%";
        return harness.jdbc().queryForObject(
                "select count(*) from agent_command "
                        + "where lower(payload_json) like ? or lower(result_json) like ? "
                        + "or lower(error_message) like ?", Long.class, pattern, pattern, pattern);
    }

    /** Canary occurrences in the recovery audit's details/reason only. */
    private long canaryInRecoveryEvidence(String canary) {
        String pattern = "%" + canary.toLowerCase() + "%";
        return harness.jdbc().queryForObject(
                "select count(*) from audit_record where action = 'agent_command.recover_transient' "
                        + "and (lower(details_json) like ? or lower(reason) like ?)",
                Long.class, pattern, pattern);
    }

    /** Canary in error_message or any recovery-audit text column. */
    private long canaryInCommandErrorOrAudit(String canary) {
        String pattern = "%" + canary.toLowerCase() + "%";
        return harness.jdbc().queryForObject(
                "select (select count(*) from agent_command where lower(error_message) like ?) "
                        + "+ (select count(*) from audit_record where action = 'agent_command.recover_transient' "
                        + "and (lower(details_json) like ? or lower(reason) like ? or lower(before_hash) like ? "
                        + "or lower(after_hash) like ?)) from dual",
                Long.class, pattern, pattern, pattern, pattern, pattern);
    }

    /** Canary occurrences in any audit_record text column (any action). */
    private long canaryInAnyAudit(String canary) {
        String pattern = "%" + canary.toLowerCase() + "%";
        return harness.jdbc().queryForObject(
                "select count(*) from audit_record where lower(details_json) like ? "
                        + "or lower(reason) like ? or lower(before_hash) like ? "
                        + "or lower(after_hash) like ? or lower(resource_id) like ? "
                        + "or lower(action) like ?",
                Long.class, pattern, pattern, pattern, pattern, pattern, pattern);
    }
}
