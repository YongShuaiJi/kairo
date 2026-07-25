package com.example.kairo.platform.command;

import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V1.7 M1-D &sect;8.4 test support: builds valid runtime-state snapshots (matching the M1-C
 * validation, including the fixed-point {@code serializedBytes}), persists them through the real
 * {@code REFRESH_RUNTIME_STATE} ack path, and seeds the authoritative desired-state tables
 * ({@code rule}, {@code rule_version}, {@code rule_target}, {@code rule_runtime_status}) so the
 * reconciler has a desired state to converge against.
 *
 * <p>Shared by the four M1-D integration tests so the snapshot/desired shape cannot drift between
 * them. The real-JVM restart test reuses the snapshot builders for its post-restart assertions.
 */
public final class ReconciliationTestSupport {

    public static final String DEFAULT_PROJECT = "proj-default";
    public static final String DEFAULT_APPLICATION = "app-default";
    public static final String DEFAULT_ENVIRONMENT = "env-dev";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReconciliationTestSupport() {
    }

    // -------------------------------------------------------- desired-state seeding

    /**
     * Seed a trial script session (unpromoted: {@code formal_rule_id = null}). {@code expiresAt} is a
     * timestamp string (e.g. {@code "2099-01-01 00:00:00"}); {@code status} drives {@code applied_at}.
     */
    public static void seedTrialSession(JdbcTemplate jdbc, String sessionId, String agentId, String status,
                                String targetClass, String targetMethod, String targetDescriptor,
                                long ttlMillis, String expiresAt) {
        jdbc.update("""
                insert into script_session(id, agent_id, application_id, target_class_name,
                  target_class_loader_id, target_method_name, target_method_descriptor, script_hash,
                  requested_profile, effective_profile, platform_max_profile, application_max_profile,
                  policy_revision, policy_hash, ttl_millis, max_hits, status, hit_count, version,
                  idempotency_key, requested_by, formal_rule_id, agent_result_json, diagnostics_json,
                  created_at, expires_at, applied_at, updated_at, created_by, correlation_id)
                values (?, ?, ?, ?, null, ?, ?, 'hash', 'SAFE', 'SAFE', 'UNRESTRICTED', 'UNRESTRICTED',
                  0, 'policy-hash', ?, 100, ?, 0, 1, ?, 'test', null, '{}', '[]',
                  current_timestamp, ?,
                  case when ? = 'APPLIED' then current_timestamp else null end,
                  current_timestamp, 'test', 'corr')
                """, sessionId, agentId, DEFAULT_APPLICATION, targetClass, targetMethod, targetDescriptor,
                ttlMillis, status, sessionId + ":idem", expiresAt, status);
    }

    /** Seed a formal rule version (ENABLED) + its target + a runtime-status for the instance. */
    public static void seedDesiredRule(JdbcTemplate jdbc, String ruleId, long version, String instanceId,
                                String className, String loaderId, String methodName,
                                String descriptor, String location, String scriptJson,
                                String runtimeStatus) {
        jdbc.update("""
                insert into rule(id, application_id, environment_id, name, status, latest_version,
                  current_draft_version, created_by, created_at, updated_by, updated_at)
                values (?, ?, ?, ?, 'ENABLED', ?, ?, 'system', current_timestamp,
                  'system', current_timestamp)
                """, ruleId, DEFAULT_APPLICATION, DEFAULT_ENVIRONMENT, ruleId, version, version);
        String versionId = ruleId + ":" + version;
        jdbc.update("""
                insert into rule_version(id, rule_id, version, status, risk_level, matcher_json,
                  script_hash, script_json, governance_json, created_by, created_at)
                values (?, ?, ?, 'ENABLED', 'LOW', '{}', ?, ?, '{}', 'system', current_timestamp)
                """, versionId, ruleId, version, hashOf(ruleId + version), scriptJson);
        Map<String, Object> matcher = new LinkedHashMap<>();
        matcher.put("classId", className);
        matcher.put("classLoaderId", loaderId);
        matcher.put("descriptor", descriptor);
        jdbc.update("""
                insert into rule_target(id, rule_version_id, protocol, class_name, method_name,
                  matcher_json, location, created_at)
                values (?, ?, 'INSTRUMENTATION', ?, ?, ?, ?, current_timestamp)
                """, "rt-" + versionId, versionId, className, methodName,
                PlatformJson.write(matcher), location == null ? null : location);
        jdbc.update("""
                insert into rule_runtime_status(id, rule_id, rule_version, instance_id, status,
                  hit_count, error_count, updated_at)
                values (?, ?, ?, ?, ?, 0, 0, current_timestamp)
                """, "rrs-" + ruleId + ":" + version + "#" + instanceId.hashCode(),
                ruleId, version, instanceId, runtimeStatus);
    }

    /** Set the rule version status (ENABLED/DISABLED) without re-seeding. */
    public static void setRuleVersionStatus(JdbcTemplate jdbc, String ruleId, long version, String status) {
        jdbc.update("update rule_version set status = ? where rule_id = ? and version = ?",
                status, ruleId, version);
    }

    /** Flip a runtime status (ACTIVE/REMOVED) without re-seeding. */
    public static void setRuntimeStatus(JdbcTemplate jdbc, String ruleId, long version,
                                 String instanceId, String status) {
        jdbc.update("update rule_runtime_status set status = ? where rule_id = ? and rule_version = ? and instance_id = ?",
                status, ruleId, version, instanceId);
    }

    /** V1.7 M1-E &sect;8.5: insert a rule_runtime_status row for an instance (rule already seeded). */
    public static void seedRuleRuntimeStatus(JdbcTemplate jdbc, String ruleId, long version,
                                             String instanceId, String status) {
        jdbc.update("""
                insert into rule_runtime_status(id, rule_id, rule_version, instance_id, status,
                  hit_count, error_count, last_error, updated_at)
                values (?, ?, ?, ?, ?, 0, 0, null, current_timestamp)
                """, "rrs-" + ruleId + ":" + version + "#" + instanceId.hashCode(),
                ruleId, version, instanceId, status);
    }

    public static void seedInstance(JdbcTemplate jdbc, String agentId, String instanceId, String processStartId) {
        jdbc.update("""
                insert into instance(id, application_id, environment_id, nickname, hostname, process_id, runtime,
                  status, labels_json, process_start_id, created_at, updated_at)
                values (?, ?, ?, ?, 'localhost', '1', 'java', 'ACTIVE', '{}', ?, current_timestamp, current_timestamp)
                """, instanceId, DEFAULT_APPLICATION, DEFAULT_ENVIRONMENT, instanceId, processStartId);
        jdbc.update("""
                insert into agent_instance(id, instance_id, status, agent_version, bootstrap_version,
                  listen_host, listen_port, token_hash, capabilities_json, created_at, updated_at)
                values (?, ?, 'ACTIVE', 'test', 'test', '127.0.0.1', 1, 'hash-only', '[]',
                  current_timestamp, current_timestamp)
                """, agentId, instanceId);
    }

    /**
     * V1.7 M1-E &sect;8.5: seed a SUCCEEDED rule rollout operation_plan (version 2, matching the
     * controller's create-&gt;RUNNING-&gt;SUCCEEDED lifecycle) so an unload can be submitted against it.
     */
    public static void seedSucceededOperation(JdbcTemplate jdbc, String operationId, String ruleId, long version) {
        jdbc.update("""
                insert into operation_plan(id, application_id, environment_id, plan_type, resource_type,
                  resource_id, resource_version, status, version, strategy_json, created_by,
                  created_at, updated_by, updated_at, terminal_source, terminal_reason)
                values (?, ?, ?, 'RULE_ROLLOUT', 'rule', ?, ?, 'SUCCEEDED', 2, '{}', 'system',
                  current_timestamp, 'system', current_timestamp, '', '')
                """, operationId, DEFAULT_APPLICATION, DEFAULT_ENVIRONMENT, ruleId, version);
    }

    /** V1.7 M1-E &sect;8.5: seed a rollout_instance_execution in a given status (SUCCEEDED by default). */
    public static void seedExecution(JdbcTemplate jdbc, String executionId, String operationId,
                                     String instanceId, long ruleVersion, String status) {
        jdbc.update("""
                insert into rollout_instance_execution(id, rollout_batch_id, operation_plan_id, instance_id,
                  status, expected_agent_version, expected_rule_version, command_id, error_message,
                  started_at, finished_at, version, updated_by, updated_at)
                values (?, null, ?, ?, ?, '0.1.0', ?, null, null, current_timestamp, current_timestamp, 1,
                  'system', current_timestamp)
                """, executionId, operationId, instanceId, status, ruleVersion);
    }

    /** V1.7 M1-E &sect;8.5: make an agent's lease expired so it is not reachable for an unload. */
    public static void setAgentOffline(JdbcTemplate jdbc, String agentId) {
        jdbc.update("update agent_instance set lease_expires_at = timestamp '2020-01-01 00:00:00' "
                + "where id = ?", agentId);
    }

    /** V1.7 M1-E &sect;8.5: restore an agent's lease so it is reachable again (reconnect). */
    public static void setAgentOnline(JdbcTemplate jdbc, String agentId) {
        jdbc.update("update agent_instance set lease_expires_at = timestamp '2099-01-01 00:00:00' "
                + "where id = ?", agentId);
    }

    // -------------------------------------------------------- snapshot persistence (REFRESH ack)

    /**
     * Persist a snapshot row directly (bypassing the REFRESH ack path) so a platform-only test can
     * set up a precise actual state without the post-ack reconciliation trigger firing. The JSON
     * and byte count mirror what M1-C would persist.
     */
    public static void persistSnapshotDirect(JdbcTemplate jdbc, String agentId, String instanceId,
                                     String processStartId, Map<String, Object> snapshot) {
        AgentRuntimeSnapshot dto = MAPPER.convertValue(snapshot, AgentRuntimeSnapshot.class);
        byte[] bytes = PlatformJson.bytes(dto);
        int ruleCount = dto.rules() == null ? 0 : dto.rules().size();
        int chainCount = dto.chains() == null ? 0 : dto.chains().size();
        int degradedCount = dto.degradedClasses() == null ? 0 : dto.degradedClasses().size();
        jdbc.update("""
                delete from agent_runtime_state where agent_id = ?
                """, agentId);
        jdbc.update("""
                insert into agent_runtime_state(agent_id, instance_id, process_start_id, protocol_version,
                  agent_version, observed_at, received_at, disabled, rule_count, chain_count,
                  degraded_class_count, serialized_bytes, snapshot_json, created_at, updated_at)
                values (?, ?, ?, ?, ?, current_timestamp, current_timestamp, ?, ?, ?, ?, ?, ?,
                  current_timestamp, current_timestamp)
                """, agentId, instanceId, processStartId, dto.protocolVersion(), dto.agentVersion(),
                dto.disabled(), ruleCount, chainCount, degradedCount, bytes.length,
                PlatformJson.write(dto));
    }

    /** Enqueue a REFRESH, poll it, ack with the snapshot so M1-C validates+persists it. */
    public static void refreshAndAck(AgentCommandService commands, RequestContext admin, RequestContext agentCtx,
                              String agentId, Map<String, Object> snapshot) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", "REFRESH_RUNTIME_STATE");
        request.put("maxAttempts", 5);
        String commandId = String.valueOf(commands.createManualCommand(admin, agentId, request).get("id"));
        Map<String, Object> polled = commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        if (!"DISPATCHED".equals(polled.get("status")) || !commandId.equals(polled.get("id"))) {
            throw new IllegalStateException("REFRESH not dispatched for " + agentId + ": " + polled);
        }
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("status", "ACKED");
        ack.put("expectedAttempts", 1L);
        ack.put("reason", "test refresh");
        ack.put("result", snapshot);
        commands.ack(commandId, agentCtx, ack);
    }

    /** Enqueue + poll a convergence command so a test can ack it (or leave it PENDING/DISPATCHED). */
    public static String enqueueAndPoll(AgentCommandService commands, RequestContext admin, RequestContext agentCtx,
                                String agentId, String commandType) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("commandType", commandType);
        request.put("maxAttempts", 5);
        String commandId = String.valueOf(commands.createManualCommand(admin, agentId, request).get("id"));
        commands.pollNext(agentId, agentCtx, Map.of("leaseSeconds", 60));
        return commandId;
    }

    // -------------------------------------------------------- snapshot builders

    /** A valid snapshot with one ACTIVE chain carrying {@code ruleIds}. */
    public static Map<String, Object> snapshot(String agentId, String processStartId, Map<String, Object> chain,
                                        List<String> ruleIds, List<Map<String, Object>> rules) {
        return snapshot(agentId, processStartId, List.of(chain), rules);
    }

    /** A valid snapshot with multiple chains, used to prove target selection never picks blindly. */
    public static Map<String, Object> snapshot(String agentId, String processStartId,
                                              List<Map<String, Object>> chains,
                                              List<Map<String, Object>> rules) {
        Map<String, Object> truncation = new LinkedHashMap<>();
        truncation.put("rules", truncationEntry(rules.size(), rules.size()));
        truncation.put("chains", truncationEntry(chains.size(), chains.size()));
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
        snapshot.put("chains", new ArrayList<>(chains));
        snapshot.put("rules", new ArrayList<>(rules));
        snapshot.put("degradedClasses", new ArrayList<>());
        snapshot.put("truncation", truncation);
        return fixSerializedBytes(snapshot);
    }

    public static Map<String, Object> emptySnapshot(String agentId, String processStartId) {
        Map<String, Object> truncation = new LinkedHashMap<>();
        truncation.put("rules", truncationEntry(0, 0));
        truncation.put("chains", truncationEntry(0, 0));
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
        snapshot.put("chains", new ArrayList<>());
        snapshot.put("rules", new ArrayList<>());
        snapshot.put("degradedClasses", new ArrayList<>());
        snapshot.put("truncation", truncation);
        return fixSerializedBytes(snapshot);
    }

    public static Map<String, Object> chain(String chainId, String className, String loaderId,
                                    String methodName, String descriptor, String location,
                                    String desiredState, List<String> ruleIds, String degradedReason) {
        Map<String, Object> chain = new LinkedHashMap<>();
        chain.put("chainId", chainId);
        chain.put("className", className);
        chain.put("loaderId", loaderId);
        chain.put("methodName", methodName);
        chain.put("descriptor", descriptor);
        chain.put("location", location);
        chain.put("appliedRevision", 1L);
        chain.put("canonicalHash", "ca973c5a3d68e3a3571083fe837317fc7afbb45d61529c3a2159626f0670808f");
        chain.put("transformationRevision", 0L);
        chain.put("transformationHash", "");
        chain.put("desiredState", desiredState);
        chain.put("ruleIds", new ArrayList<>(ruleIds));
        if (degradedReason != null) {
            chain.put("degradedReason", degradedReason);
        }
        return chain;
    }

    public static Map<String, Object> rule(String ruleId, long version) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleId", ruleId);
        rule.put("ruleVersion", version);
        rule.put("enabled", true);
        rule.put("expireAt", 0L);
        return rule;
    }

    private static Map<String, Object> truncationEntry(int total, int included) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("total", total);
        entry.put("included", included);
        return entry;
    }

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

    private static String hashOf(String value) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
