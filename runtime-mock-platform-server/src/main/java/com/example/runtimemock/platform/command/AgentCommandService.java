package com.example.runtimemock.platform.command;

import com.example.runtimemock.platform.service.PlatformException;
import com.example.runtimemock.platform.service.PlatformJdbcService;
import com.example.runtimemock.platform.service.PlatformJson;
import com.example.runtimemock.platform.service.RbacService;
import com.example.runtimemock.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AgentCommandService {

    private final JdbcTemplate jdbcTemplate;
    private final RbacService rbacService;
    private final PlatformJdbcService eventWriter;
    private final Clock clock;

    @Autowired
    public AgentCommandService(JdbcTemplate jdbcTemplate, RbacService rbacService,
                               PlatformJdbcService eventWriter) {
        this(jdbcTemplate, rbacService, eventWriter, Clock.systemUTC());
    }

    AgentCommandService(JdbcTemplate jdbcTemplate, RbacService rbacService,
                        PlatformJdbcService eventWriter, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.rbacService = rbacService;
        this.eventWriter = eventWriter;
        this.clock = clock;
    }

    public List<Map<String, Object>> listCommands() {
        return normalizeRows(jdbcTemplate.queryForList("select * from agent_command order by created_at, id"));
    }

    @Transactional
    public Map<String, Object> createManualCommand(RequestContext context, String agentId,
                                                   Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        String commandType = requiredString(request, "commandType", null);
        Map<String, Object> payload = optionalMap(request, "payload");
        String idempotencyKey = optionalString(request, "idempotencyKey",
                "manual:" + agentId + ":" + commandType + ":" + UUID.randomUUID());
        return enqueue(context, agentId, commandType, payload, idempotencyKey,
                optionalLong(request, "maxAttempts", 5), clock.instant());
    }

    @Transactional
    public Map<String, Object> enqueue(RequestContext context, String agentId, String commandType,
                                       Map<String, Object> payload, String idempotencyKey,
                                       long maxAttempts, Instant availableAt) {
        requireExistingAgent(agentId);
        Instant now = clock.instant();
        String id = "agent-command-" + UUID.randomUUID();
        Map<String, Object> fullPayload = new LinkedHashMap<>(payload);
        fullPayload.putIfAbsent("protocolVersion", "v1");
        try {
            jdbcTemplate.update("""
                    insert into agent_command(
                        id, agent_id, command_type, status, idempotency_key, payload_json, result_json,
                        attempts, max_attempts, available_at, created_by, created_at, updated_at, correlation_id
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, agentId, commandType, "PENDING", idempotencyKey,
                    PlatformJson.write(fullPayload), PlatformJson.write(Map.of()), 0,
                    Math.max(maxAttempts, 1), timestamp(availableAt), context.actor(),
                    timestamp(now), timestamp(now), context.correlationId());
        } catch (DuplicateKeyException ignored) {
            return getByIdempotencyKey(idempotencyKey);
        }
        Map<String, Object> created = getById(id);
        eventWriter.recordEvent(context, "agent_command.enqueue", "agent_command", id, 1,
                "", created, "SUCCESS", "enqueue agent command",
                Map.of("agentId", agentId, "commandType", commandType, "idempotencyKey", idempotencyKey));
        return created;
    }

    @Transactional
    public Map<String, Object> pollNext(String agentId, RequestContext context, Map<String, Object> request) {
        requireAgentProtocolOrManager(agentId, context);
        requireExistingAgent(agentId);
        Instant now = clock.instant();
        Instant leaseExpiresAt = now.plusSeconds(optionalLong(request, "leaseSeconds", 60));
        List<Map<String, Object>> candidates = jdbcTemplate.queryForList("""
                select *
                  from agent_command
                 where agent_id = ?
                   and (
                        (status = 'PENDING' and available_at <= ?)
                     or (status = 'DISPATCHED' and lease_expires_at <= ? and attempts < max_attempts)
                   )
                 order by available_at, created_at, id
                 limit 1
                """, agentId, timestamp(now), timestamp(now));
        if (candidates.isEmpty()) {
            return Map.of("status", "NO_COMMAND", "agentId", agentId);
        }
        Map<String, Object> candidate = normalizeRow(candidates.get(0));
        int updated = jdbcTemplate.update("""
                update agent_command
                   set status = 'DISPATCHED',
                       attempts = attempts + 1,
                       dispatched_at = ?,
                       lease_expires_at = ?,
                       updated_at = ?
                 where id = ?
                   and status in ('PENDING', 'DISPATCHED')
                """, timestamp(now), timestamp(leaseExpiresAt), timestamp(now), candidate.get("id"));
        if (updated == 0) {
            return Map.of("status", "NO_COMMAND", "agentId", agentId);
        }
        Map<String, Object> command = getById(String.valueOf(candidate.get("id")));
        eventWriter.recordEvent(context, "agent_command.dispatch", "agent_command",
                String.valueOf(command.get("id")), ((Number) command.get("attempts")).longValue(),
                candidate, command, "SUCCESS", "dispatch agent command",
                Map.of("agentId", agentId, "leaseExpiresAt", leaseExpiresAt.toString()));
        Map<String, Object> response = new LinkedHashMap<>(command);
        response.put("payload", PlatformJson.readMap(String.valueOf(command.get("payload_json"))));
        return response;
    }

    @Transactional
    public Map<String, Object> ack(String commandId, RequestContext context, Map<String, Object> request) {
        Map<String, Object> current = getById(commandId);
        requireAgentProtocolOrManager(String.valueOf(current.get("agent_id")), context);
        String resultStatus = requiredString(request, "status", null);
        if (!"ACKED".equals(resultStatus) && !"FAILED".equals(resultStatus)) {
            throw PlatformException.badRequest("INVALID_FIELD", "status must be ACKED or FAILED");
        }
        Instant now = clock.instant();
        Map<String, Object> result = optionalMap(request, "result");
        String errorMessage = optionalString(request, "errorMessage", null);
        int updatedCount = jdbcTemplate.update("""
                update agent_command
                   set status = ?,
                       result_json = ?,
                       error_message = ?,
                       completed_at = ?,
                       updated_at = ?
                 where id = ? and status = 'DISPATCHED'
                """, resultStatus, PlatformJson.write(result), errorMessage,
                timestamp(now), timestamp(now), commandId);
        if (updatedCount == 0) {
            throw PlatformException.conflict("AGENT_COMMAND_STATE_CONFLICT",
                    "Agent command is not currently dispatched",
                    Map.of("commandId", commandId, "status", current.get("status")));
        }
        Map<String, Object> updated = getById(commandId);
        eventWriter.recordEvent(context, "agent_command.ack", "agent_command", commandId,
                ((Number) updated.get("attempts")).longValue(), current, updated, resultStatus,
                optionalString(request, "reason", "ack agent command"),
                Map.of("agentId", updated.get("agent_id"), "commandType", updated.get("command_type")));
        advanceRolloutFromCommand(context, commandId, "ACKED".equals(resultStatus), errorMessage, result);
        return updated;
    }

    private void advanceRolloutFromCommand(RequestContext context, String commandId, boolean success,
                                           String errorMessage, Map<String, Object> result) {
        List<Map<String, Object>> executions = jdbcTemplate.queryForList("""
                select *
                  from rollout_instance_execution
                 where command_id = ?
                """, commandId);
        if (executions.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (Map<String, Object> rawExecution : executions) {
            Map<String, Object> execution = normalizeRow(rawExecution);
            String executionId = String.valueOf(execution.get("id"));
            String newStatus = success ? "SUCCEEDED" : "FAILED";
            long executionVersion = ((Number) execution.get("version")).longValue();
            int executionUpdated = jdbcTemplate.update("""
                    update rollout_instance_execution
                       set status = ?,
                           error_message = ?,
                           finished_at = ?,
                           version = version + 1,
                           updated_by = ?,
                           updated_at = ?
                     where id = ? and status = 'WAITING_AGENT' and version = ?
                    """, newStatus, success ? null : errorMessage, timestamp(now),
                    context.actor(), timestamp(now), executionId, executionVersion);
            if (executionUpdated == 0) {
                continue;
            }
            Map<String, Object> updatedExecution = normalizeRow(jdbcTemplate.queryForMap(
                    "select * from rollout_instance_execution where id = ?", executionId));
            eventWriter.recordEvent(context, "rollout_instance_execution.agent_ack",
                    "rollout_instance_execution", executionId, 1, execution, updatedExecution,
                    success ? "SUCCESS" : "FAILED", "agent command completed",
                    Map.of("commandId", commandId, "result", result));
            advanceBatchAndOperation(context, String.valueOf(execution.get("rollout_batch_id")));
        }
    }

    private void advanceBatchAndOperation(RequestContext context, String batchId) {
        Map<String, Object> batch = normalizeRow(jdbcTemplate.queryForMap(
                "select * from rollout_batch where id = ?", batchId));
        List<Map<String, Object>> executions = normalizeRows(jdbcTemplate.queryForList("""
                select * from rollout_instance_execution where rollout_batch_id = ?
                """, batchId));
        if (executions.isEmpty()) {
            return;
        }
        boolean allTerminal = executions.stream()
                .allMatch(row -> terminalExecutionStatus(String.valueOf(row.get("status"))));
        if (!allTerminal) {
            return;
        }
        boolean allSucceeded = executions.stream()
                .allMatch(row -> "SUCCEEDED".equals(String.valueOf(row.get("status"))));
        String batchStatus = allSucceeded ? "SUCCEEDED" : "FAILED";
        Instant now = clock.instant();
        long batchVersion = ((Number) batch.get("version")).longValue();
        int batchUpdated = jdbcTemplate.update("""
                update rollout_batch
                   set status = ?, version = version + 1, updated_by = ?, updated_at = ?
                 where id = ? and status = 'RUNNING' and version = ?
                """, batchStatus, context.actor(), timestamp(now), batchId, batchVersion);
        if (batchUpdated == 0) {
            return;
        }
        Map<String, Object> updatedBatch = normalizeRow(jdbcTemplate.queryForMap(
                "select * from rollout_batch where id = ?", batchId));
        eventWriter.recordEvent(context, "rollout_batch.complete", "rollout_batch", batchId,
                1, batch, updatedBatch, batchStatus, "rollout batch completed",
                Map.of("executionCount", executions.size()));
        advanceOperation(context, String.valueOf(batch.get("operation_plan_id")));
    }

    private void advanceOperation(RequestContext context, String operationPlanId) {
        Map<String, Object> operation = normalizeRow(jdbcTemplate.queryForMap(
                "select * from operation_plan where id = ?", operationPlanId));
        if (!"RUNNING".equals(String.valueOf(operation.get("status")))) {
            return;
        }
        List<Map<String, Object>> batches = normalizeRows(jdbcTemplate.queryForList("""
                select * from rollout_batch where operation_plan_id = ?
                """, operationPlanId));
        if (batches.isEmpty()) {
            return;
        }
        boolean allTerminal = batches.stream()
                .allMatch(row -> terminalBatchStatus(String.valueOf(row.get("status"))));
        if (!allTerminal) {
            return;
        }
        boolean allSucceeded = batches.stream()
                .allMatch(row -> "SUCCEEDED".equals(String.valueOf(row.get("status"))));
        String newStatus = allSucceeded ? "SUCCEEDED" : "FAILED";
        long version = ((Number) operation.get("version")).longValue() + 1;
        Instant now = clock.instant();
        long currentVersion = ((Number) operation.get("version")).longValue();
        int operationUpdated = jdbcTemplate.update("""
                update operation_plan
                   set status = ?, version = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = 'RUNNING' and version = ?
                """, newStatus, version, context.actor(), timestamp(now), operationPlanId, currentVersion);
        if (operationUpdated == 0) {
            return;
        }
        Map<String, Object> updatedOperation = normalizeRow(jdbcTemplate.queryForMap(
                "select * from operation_plan where id = ?", operationPlanId));
        eventWriter.recordEvent(context, "operation_plan.auto_complete", "operation_plan", operationPlanId,
                version, operation, updatedOperation, newStatus, "rollout operation completed",
                Map.of("batchCount", batches.size()));
    }

    private boolean terminalExecutionStatus(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean terminalBatchStatus(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private void requireExistingAgent(String agentId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from agent_instance where id = ?", Integer.class, agentId);
        if (count == null || count == 0) {
            throw PlatformException.notFound("agent_instance", agentId);
        }
    }

    private void requireAgentProtocolOrManager(String agentId, RequestContext context) {
        if (agentId.equals(context.actor()) && "agent".equals(context.identitySource())) {
            return;
        }
        rbacService.require(context, "AGENT_MANAGE");
    }

    private Map<String, Object> getById(String id) {
        try {
            return normalizeRow(jdbcTemplate.queryForMap("select * from agent_command where id = ?", id));
        } catch (Exception e) {
            throw PlatformException.notFound("agent_command", id);
        }
    }

    private Map<String, Object> getByIdempotencyKey(String idempotencyKey) {
        return normalizeRow(jdbcTemplate.queryForMap(
                "select * from agent_command where idempotency_key = ?", idempotencyKey));
    }

    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        return rows.stream().map(this::normalizeRow).toList();
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(), value));
        return normalized;
    }

    private Map<String, Object> optionalMap(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return PlatformJson.stringKeyMap(map);
        }
        if (value instanceof String text) {
            return PlatformJson.readMap(text);
        }
        throw PlatformException.badRequest("INVALID_FIELD", "Field must be an object: " + key);
    }

    private String requiredString(Map<String, Object> request, String key, String defaultValue) {
        String value = optionalString(request, key, defaultValue);
        if (value == null || value.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "Missing required field: " + key);
        }
        return value;
    }

    private String optionalString(Map<String, Object> request, String key, String defaultValue) {
        Object value = request.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private long optionalLong(Map<String, Object> request, String key, long defaultValue) {
        Object value = request.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
