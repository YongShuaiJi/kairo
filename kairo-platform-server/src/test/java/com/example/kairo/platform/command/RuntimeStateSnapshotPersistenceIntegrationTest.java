package com.example.kairo.platform.command;

import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V1.7 M1-C &sect;8.3: Platform validation and persistence of the Agent runtime-state snapshot
 * carried in a {@code REFRESH_RUNTIME_STATE} ack. Runs through the real {@link AgentCommandService}
 * Spring proxy so the snapshot validation+persist step shares the ACK transaction: a validation
 * failure rolls the ACK back (the command reverts to {@code DISPATCHED}, never falsely ACKED) and
 * no actual state is overwritten. M1-A lease/epoch fencing and M1-B restart recovery are untouched.
 *
 * <p>Covers: successful validation and replacement of the latest snapshot; strict nested rejection
 * (unknown fields, unsorted collections/ruleIds, invalid enums, negative revisions, truncation
 * inconsistencies, byteLimit/serializedBytes mismatch, included != list size); oversized and
 * malformed payload rejection; stale {@code processStartId} rejection with no ACK and no overwrite;
 * duplicate-ack behaviour consistent with M1-A; and serialized concurrent latest-snapshot
 * replacement for the same agent (deterministic, no long sleeps). A dedicated in-memory H2
 * isolates these tests.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_m1c;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class RuntimeStateSnapshotPersistenceIntegrationTest {

    private static final String PROCESS_START_ID = "snapshot-host:4242:1700000000000";
    private static final String STALE_PROCESS_START_ID = "stale-host:9999:1600000000000";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired AgentCommandService commands;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;

    private String agentId;
    private String instanceId;
    private RequestContext admin;
    private RequestContext agentCtx;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
        agentId = "agent-snap-" + UUID.randomUUID();
        instanceId = "inst-snap-" + UUID.randomUUID();
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, process_start_id, created_at, updated_at)
                values (?, 'app-default', 'env-dev', ?, 'localhost', '1', 'java', 'ACTIVE', '{}',
                  ?, current_timestamp, current_timestamp)
                """, instanceId, instanceId, PROCESS_START_ID);
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values (?, ?, 'ACTIVE', 'test', 'test', '127.0.0.1', 1, 'hash-only', '[]',
                  current_timestamp, current_timestamp)
                """, agentId, instanceId);
        admin = new RequestContext("system", "corr", "127.0.0.1", "header-dev", "test");
        agentCtx = new RequestContext(agentId, "corr", "127.0.0.1", "agent", "test");
    }

    @AfterEach
    void tearDown() {
        if (agentId != null) {
            jdbc.update("delete from agent_runtime_state where agent_id = ?", agentId);
            jdbc.update("delete from agent_command where agent_id = ?", agentId);
            jdbc.update("delete from agent_instance where id = ?", agentId);
        }
        if (instanceId != null) {
            jdbc.update("delete from instance where id = ?", instanceId);
        }
    }

    @Test
    void validSnapshotIsAcknowledgedAndPersistsLatest() {
        String cmd1 = refreshCommand();
        poll(cmd1);
        commands.ack(cmd1, agentCtx, ackRequest(snapshot(agentId, PROCESS_START_ID, "rule-a")));
        assertThat(commandStatus(cmd1)).isEqualTo("ACKED");
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(persistedSnapshotJson()).contains("rule-a");
        assertThat(persistedProcessStartId()).isEqualTo(PROCESS_START_ID);

        // A second REFRESH from the same process replaces the one current snapshot (no history).
        String cmd2 = refreshCommand();
        poll(cmd2);
        commands.ack(cmd2, agentCtx, ackRequest(snapshot(agentId, PROCESS_START_ID, "rule-b")));
        assertThat(commandStatus(cmd2)).isEqualTo("ACKED");
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(persistedSnapshotJson()).contains("rule-b").doesNotContain("rule-a");
    }

    @Test
    void agentIdMismatchIsRejectedWithNoAckAndNoPersist() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> snapshot = snapshot("wrong-agent", PROCESS_START_ID, "rule-x");
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void unknownFieldIsRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> snapshot = snapshot(agentId, PROCESS_START_ID, "rule-x");
        snapshot.put("unexpectedField", "boom");
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void unsortedRulesAreRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> snapshot = snapshot(agentId, PROCESS_START_ID, "rule-a");
        // Prepend a lexicographically-greater rule so rules are no longer sorted by ruleId.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) snapshot.get("rules");
        rules.add(0, ruleMap("rule-z"));
        ((Map<String, Object>) snapshot.get("truncation")).put("rules", truncationEntry(2, 2));
        fixSerializedBytes(snapshot);
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
    }

    @Test
    void invalidLocationEnumIsRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> snapshot = snapshot(agentId, PROCESS_START_ID, "rule-x");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chains = (List<Map<String, Object>>) snapshot.get("chains");
        chains.get(0).put("location", "BOGUS_LOCATION");
        fixSerializedBytes(snapshot);
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
    }

    @Test
    void missingLoaderIdIsRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> snapshot = snapshot(agentId, PROCESS_START_ID, "rule-x");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chains = (List<Map<String, Object>>) snapshot.get("chains");
        chains.get(0).remove("loaderId");
        fixSerializedBytes(snapshot);
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
    }

    @Test
    void negativeRevisionIsRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> snapshot = snapshot(agentId, PROCESS_START_ID, "rule-x");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chains = (List<Map<String, Object>>) snapshot.get("chains");
        chains.get(0).put("appliedRevision", -1L);
        fixSerializedBytes(snapshot);
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
    }

    @Test
    void truncationIncludedMismatchIsRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> snapshot = snapshot(agentId, PROCESS_START_ID, "rule-x");
        // Claim 2 included rules while the list has 1.
        ((Map<String, Object>) snapshot.get("truncation")).put("rules", truncationEntry(2, 2));
        fixSerializedBytes(snapshot);
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
    }

    @Test
    void truncationByteLimitMismatchIsRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> snapshot = snapshot(agentId, PROCESS_START_ID, "rule-x");
        ((Map<String, Object>) snapshot.get("truncation")).put("byteLimit", 999L);
        fixSerializedBytes(snapshot);
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
    }

    @Test
    void truncationSerializedBytesMismatchIsRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> snapshot = snapshot(agentId, PROCESS_START_ID, "rule-x");
        // A wrong serializedBytes (still within the limit) must be rejected.
        ((Map<String, Object>) snapshot.get("truncation")).put("serializedBytes", 1L);
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
    }

    @Test
    void truncationReasonInconsistentIsRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> snapshot = snapshot(agentId, PROCESS_START_ID, "rule-x");
        // included < total but reason is null (should be ENTRY_COUNT_LIMIT or SERIALIZED_BYTE_LIMIT).
        ((Map<String, Object>) snapshot.get("truncation")).put("rules", truncationEntry(5, 1));
        fixSerializedBytes(snapshot);
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
    }

    @Test
    void malformedPayloadIsRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", "ACKED");
        request.put("expectedAttempts", 1L);
        request.put("result", Map.of()); // empty -> missing snapshot
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, request))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void oversizedPayloadIsRejected() {
        String cmd = refreshCommand();
        poll(cmd);
        // A single chain with a 2 MiB canonical hash: within the chain count bound but the
        // serialized bytes exceed the 1 MiB cap, so the byte-size validation rejects it.
        Map<String, Object> snapshot = snapshot(agentId, PROCESS_START_ID, "rule-big");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> chains = (List<Map<String, Object>>) snapshot.get("chains");
        chains.get(0).put("canonicalHash", "x".repeat(2 * 1024 * 1024));
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("INVALID_FIELD"));
        assertThat(commandStatus(cmd)).isEqualTo("DISPATCHED");
        assertThat(snapshotCount()).isZero();
    }

    @Test
    void staleProcessStartIdIsRejectedWithNoAckAndNoOverwrite() {
        // First, a valid snapshot from the current process is persisted.
        String cmd1 = refreshCommand();
        poll(cmd1);
        commands.ack(cmd1, agentCtx, ackRequest(snapshot(agentId, PROCESS_START_ID, "rule-current")));
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(persistedSnapshotJson()).contains("rule-current");

        // A late ACK from an old processStartId is rejected: no ACK, no overwrite.
        String cmd2 = refreshCommand();
        poll(cmd2);
        Map<String, Object> stale = snapshot(agentId, STALE_PROCESS_START_ID, "rule-stale");
        assertThatThrownBy(() -> commands.ack(cmd2, agentCtx, ackRequest(stale)))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> {
                    PlatformException pe = (PlatformException) t;
                    assertThat(pe.code()).isEqualTo("AGENT_COMMAND_STATE_CONFLICT");
                    assertThat(pe.details()).containsEntry("snapshotProcessStartId", STALE_PROCESS_START_ID);
                    assertThat(pe.details()).containsEntry("currentProcessStartId", PROCESS_START_ID);
                });
        assertThat(commandStatus(cmd2)).isEqualTo("DISPATCHED");
        // The current actual state is untouched: the prior snapshot remains.
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(persistedSnapshotJson()).contains("rule-current").doesNotContain("rule-stale");
    }

    @Test
    void duplicateAckIsFencedOutConsistentWithM1A() {
        String cmd = refreshCommand();
        poll(cmd);
        commands.ack(cmd, agentCtx, ackRequest(snapshot(agentId, PROCESS_START_ID, "rule-once")));
        assertThat(commandStatus(cmd)).isEqualTo("ACKED");
        assertThat(snapshotCount()).isEqualTo(1);
        long ackEvents = ackEventCount(cmd);
        assertThat(ackEvents).isEqualTo(1L);

        // A duplicate ack on a terminal command is fenced out (M1-A); the snapshot step never
        // re-runs, so the persisted snapshot is not advanced or duplicated.
        assertThatThrownBy(() -> commands.ack(cmd, agentCtx, ackRequest(snapshot(agentId, PROCESS_START_ID, "rule-twice"))))
                .isInstanceOf(PlatformException.class)
                .satisfies(t -> assertThat(((PlatformException) t).code()).isEqualTo("AGENT_COMMAND_STATE_CONFLICT"));
        assertThat(commandStatus(cmd)).isEqualTo("ACKED");
        assertThat(snapshotCount()).isEqualTo(1);
        assertThat(persistedSnapshotJson()).contains("rule-once").doesNotContain("rule-twice");
        assertThat(ackEventCount(cmd)).isEqualTo(ackEvents);
    }

    @Test
    void concurrentRefreshAcksSerializeAndLeaveExactlyOneSnapshot() throws Exception {
        // Two valid REFRESH acks for the same agent race the delete+insert replacement. The agent
        // registration row is locked (SELECT ... FOR UPDATE) for the ACK transaction, so the two
        // replacements serialize: both acks succeed, no primary-key violation, and exactly one
        // complete valid snapshot remains. Deterministic (barrier, no long sleeps).
        String cmd1 = refreshCommand();
        String cmd2 = refreshCommand();
        poll(cmd1);
        poll(cmd2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> f1 = pool.submit(() -> {
                barrier.await(15, TimeUnit.SECONDS);
                commands.ack(cmd1, agentCtx, ackRequest(snapshot(agentId, PROCESS_START_ID, "rule-1")));
                return commandStatus(cmd1);
            });
            Future<String> f2 = pool.submit(() -> {
                barrier.await(15, TimeUnit.SECONDS);
                commands.ack(cmd2, agentCtx, ackRequest(snapshot(agentId, PROCESS_START_ID, "rule-2")));
                return commandStatus(cmd2);
            });
            assertThat(f1.get(30, TimeUnit.SECONDS)).isEqualTo("ACKED");
            assertThat(f2.get(30, TimeUnit.SECONDS)).isEqualTo("ACKED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(commandStatus(cmd1)).isEqualTo("ACKED");
        assertThat(commandStatus(cmd2)).isEqualTo("ACKED");
        assertThat(snapshotCount()).isEqualTo(1);
        String json = persistedSnapshotJson();
        assertThat(json).containsAnyOf("rule-1", "rule-2");
    }

    // -------------------------------------------------------- helpers

    private String refreshCommand() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", "REFRESH_RUNTIME_STATE");
        request.put("maxAttempts", 5);
        return String.valueOf(commands.createManualCommand(admin, agentId, request).get("id"));
    }

    private void poll(String commandId) {
        Map<String, Object> polled = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        assertThat(polled.get("status")).isEqualTo("DISPATCHED");
        assertThat(polled.get("id")).isEqualTo(commandId);
    }

    private static Map<String, Object> ackRequest(Map<String, Object> snapshot) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", "ACKED");
        request.put("expectedAttempts", 1L);
        request.put("result", snapshot);
        return request;
    }

    /** A valid snapshot Map matching the {@code AgentRuntimeSnapshot} wire shape, with one rule. */
    private static Map<String, Object> snapshot(String agentId, String processStartId, String ruleId) {
        Map<String, Object> chain = chainMap(ruleId);

        Map<String, Object> truncation = new LinkedHashMap<>();
        truncation.put("rules", truncationEntry(1, 1));
        truncation.put("chains", truncationEntry(1, 1));
        truncation.put("degradedClasses", truncationEntry(0, 0));
        truncation.put("byteLimit", 1048576L);
        truncation.put("serializedBytes", 0L);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("protocolVersion", "v1");
        snapshot.put("agentId", agentId);
        snapshot.put("processStartId", processStartId);
        snapshot.put("observedAt", System.currentTimeMillis());
        snapshot.put("agentVersion", "0.1.0-SNAPSHOT");
        snapshot.put("disabled", false);
        snapshot.put("chains", new java.util.ArrayList<>(List.of(chain)));
        snapshot.put("rules", new java.util.ArrayList<>(List.of(ruleMap(ruleId))));
        snapshot.put("degradedClasses", new java.util.ArrayList<>());
        snapshot.put("truncation", truncation);
        return fixSerializedBytes(snapshot);
    }

    private static Map<String, Object> ruleMap(String ruleId) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleId", ruleId);
        rule.put("ruleVersion", 1L);
        rule.put("enabled", true);
        rule.put("expireAt", 0L);
        return rule;
    }

    private static Map<String, Object> chainMap(String ruleId) {
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("chainId", "com.test.Svc#compute#METHOD_ENTER");
        chain.put("className", "com.test.Svc");
        chain.put("loaderId", "loader-1");
        chain.put("methodName", "compute");
        chain.put("descriptor", "(I)I");
        chain.put("location", "METHOD_ENTER");
        chain.put("appliedRevision", 1L);
        chain.put("canonicalHash", "ca973c5a3d68e3a3571083fe837317fc7afbb45d61529c3a2159626f0670808f");
        chain.put("transformationRevision", 0L);
        chain.put("transformationHash", "");
        chain.put("desiredState", "ACTIVE");
        chain.put("ruleIds", new java.util.ArrayList<>(List.of(ruleId)));
        return chain;
    }

    private static Map<String, Object> truncationEntry(int total, int included) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("total", total);
        entry.put("included", included);
        return entry;
    }

    /**
     * Resolve the fixed-point {@code truncation.serializedBytes} so the snapshot matches the
     * platform's deterministic serialized byte count (the field is part of its own measurement).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> fixSerializedBytes(Map<String, Object> snapshot) {
        Map<String, Object> truncation = (Map<String, Object>) snapshot.get("truncation");
        long reported = 0L;
        for (int i = 0; i < 6; i++) {
            truncation.put("serializedBytes", reported);
            AgentRuntimeSnapshot dto = MAPPER.convertValue(snapshot, AgentRuntimeSnapshot.class);
            long measured = PlatformJson.bytes(dto).length;
            if (measured == reported) {
                break;
            }
            reported = measured;
        }
        truncation.put("serializedBytes", reported);
        return snapshot;
    }

    private String commandStatus(String id) {
        return jdbc.queryForObject("select status from agent_command where id = ?", String.class, id);
    }

    private long snapshotCount() {
        Long count = jdbc.queryForObject(
                "select count(*) from agent_runtime_state where agent_id = ?", Long.class, agentId);
        return count == null ? 0L : count;
    }

    private String persistedSnapshotJson() {
        return jdbc.queryForObject(
                "select snapshot_json from agent_runtime_state where agent_id = ?", String.class, agentId);
    }

    private String persistedProcessStartId() {
        return jdbc.queryForObject(
                "select process_start_id from agent_runtime_state where agent_id = ?",
                String.class, agentId);
    }

    private long ackEventCount(String commandId) {
        Long count = jdbc.queryForObject(
                "select count(*) from audit_record where action = 'agent_command.ack' and resource_id = ?",
                Long.class, commandId);
        return count == null ? 0L : count;
    }
}
