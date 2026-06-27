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

    public Map<String, Object> command(String id) {
        return getById(id);
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
                        , rollback_execution_id
                    ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, agentId, commandType, "PENDING", idempotencyKey,
                    PlatformJson.write(fullPayload), PlatformJson.write(Map.of()), 0,
                    Math.max(maxAttempts, 1), timestamp(availableAt), context.actor(),
                    timestamp(now), timestamp(now), context.correlationId(),
                    optionalString(fullPayload, "rollbackExecutionId", null));
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
        advanceRollbackFromCommand(context, updated);
        return updated;
    }

    private void advanceRollbackFromCommand(RequestContext context, Map<String, Object> command) {
        Object rollbackValue = command.get("rollback_execution_id");
        if (rollbackValue == null || String.valueOf(rollbackValue).isBlank()) {
            return;
        }
        String rollbackId = String.valueOf(rollbackValue);
        List<Map<String, Object>> commands = normalizeRows(jdbcTemplate.queryForList("""
                select * from agent_command where rollback_execution_id = ?
                """, rollbackId));
        if (commands.isEmpty() || commands.stream().anyMatch(row ->
                !"ACKED".equals(String.valueOf(row.get("status")))
                        && !"FAILED".equals(String.valueOf(row.get("status"))))) {
            return;
        }
        boolean succeeded = commands.stream()
                .allMatch(row -> "ACKED".equals(String.valueOf(row.get("status"))));
        Map<String, Object> rollback = normalizeRow(jdbcTemplate.queryForMap(
                "select * from rollback_execution where id = ?", rollbackId));
        if (!"DISPATCHED".equals(String.valueOf(rollback.get("status")))) {
            return;
        }
        Instant now = clock.instant();
        String rollbackStatus = succeeded ? "SUCCEEDED" : "FAILED";
        int rollbackUpdated = jdbcTemplate.update("""
                update rollback_execution
                   set status = ?, finished_at = ?
                 where id = ? and status = 'DISPATCHED'
                """, rollbackStatus, timestamp(now), rollbackId);
        if (rollbackUpdated == 0) {
            return;
        }
        String operationPlanId = String.valueOf(rollback.get("operation_plan_id"));
        String operationStatus = succeeded ? "ROLLED_BACK" : "FAILED";
        jdbcTemplate.update("""
                update operation_plan
                   set status = ?, version = version + 1, updated_by = ?, updated_at = ?
                 where id = ? and status = 'ROLLING_BACK'
                """, operationStatus, context.actor(), timestamp(now), operationPlanId);
        if (succeeded) {
            jdbcTemplate.update("""
                    update rule_runtime_status
                       set status = 'REMOVED', last_error = null, updated_at = ?
                     where (rule_id, rule_version, instance_id) in (
                         select op.resource_id, op.resource_version, rie.instance_id
                           from operation_plan op
                           join rollout_instance_execution rie on rie.operation_plan_id = op.id
                          where op.id = ? and rie.status = 'SUCCEEDED'
                     )
                    """, timestamp(now), operationPlanId);
        }
        Map<String, Object> updatedOperation = normalizeRow(jdbcTemplate.queryForMap(
                "select * from operation_plan where id = ?", operationPlanId));
        eventWriter.recordEvent(context, "operation_plan.unload_complete", "operation_plan",
                operationPlanId, ((Number) updatedOperation.get("version")).longValue(),
                rollback, updatedOperation, operationStatus,
                succeeded ? "规则字节码卸载完成" : "规则字节码卸载失败",
                Map.of("rollbackExecutionId", rollbackId, "commandCount", commands.size()));
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
            advanceOperation(context, String.valueOf(execution.get("operation_plan_id")));
        }
    }

    private void advanceOperation(RequestContext context, String operationPlanId) {
        Map<String, Object> operation = normalizeRow(jdbcTemplate.queryForMap(
                "select * from operation_plan where id = ?", operationPlanId));
        if (!"RUNNING".equals(String.valueOf(operation.get("status")))) {
            return;
        }
        List<Map<String, Object>> executions = normalizeRows(jdbcTemplate.queryForList("""
                select * from rollout_instance_execution where operation_plan_id = ?
                """, operationPlanId));
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
        if (!allSucceeded && automaticRollbackEnabled(operation)) {
            startAutomaticRollback(context, operation, executions);
            return;
        }
        String newStatus = allSucceeded ? "SUCCEEDED" : "FAILED";
        Instant now = clock.instant();
        long version = ((Number) operation.get("version")).longValue() + 1;
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
        if (allSucceeded && "rule".equals(String.valueOf(operation.get("resource_type")))) {
            String ruleId = String.valueOf(operation.get("resource_id"));
            long ruleVersion = ((Number) operation.get("resource_version")).longValue();
            jdbcTemplate.update("""
                    update rule_version set status = 'PUBLISHED'
                     where rule_id = ? and version = ?
                    """, ruleId, ruleVersion);
            jdbcTemplate.update("""
                    update rule set status = 'ACTIVE', updated_by = ?, updated_at = ?
                     where id = ?
                    """, context.actor(), timestamp(now), ruleId);
            List<Map<String, Object>> successfulExecutions = normalizeRows(jdbcTemplate.queryForList("""
                    select instance_id
                      from rollout_instance_execution
                     where operation_plan_id = ? and status = 'SUCCEEDED'
                    """, operationPlanId));
            for (Map<String, Object> execution : successfulExecutions) {
                String instanceId = String.valueOf(execution.get("instance_id"));
                int runtimeStatusUpdated = jdbcTemplate.update("""
                        update rule_runtime_status
                           set status = 'ACTIVE', last_error = null, updated_at = ?
                         where rule_id = ? and rule_version = ? and instance_id = ?
                        """, timestamp(now), ruleId, ruleVersion, instanceId);
                if (runtimeStatusUpdated == 0) {
                    jdbcTemplate.update("""
                            insert into rule_runtime_status(
                                id, rule_id, rule_version, instance_id, status,
                                hit_count, error_count, last_error, updated_at
                            ) values (?, ?, ?, ?, 'ACTIVE', 0, 0, null, ?)
                            """, "rule-runtime-" + UUID.randomUUID(), ruleId, ruleVersion,
                            instanceId, timestamp(now));
                }
            }
        }
        eventWriter.recordEvent(context, "operation_plan.auto_complete", "operation_plan", operationPlanId,
                version, operation, updatedOperation, newStatus, "rollout operation completed",
                Map.of("executionCount", executions.size()));
    }

    private boolean terminalExecutionStatus(String status) {
        return "SUCCEEDED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    private boolean automaticRollbackEnabled(Map<String, Object> operation) {
        Map<String, Object> strategy =
                PlatformJson.readMap(String.valueOf(operation.get("strategy_json")));
        return Boolean.parseBoolean(String.valueOf(strategy.getOrDefault("automaticRollback", true)));
    }

    private void startAutomaticRollback(RequestContext context, Map<String, Object> operation,
                                        List<Map<String, Object>> executions) {
        String operationPlanId = String.valueOf(operation.get("id"));
        Instant now = clock.instant();
        int transitioned = jdbcTemplate.update("""
                update operation_plan
                   set status = 'ROLLING_BACK', version = version + 1, updated_by = ?, updated_at = ?
                 where id = ? and status = 'RUNNING' and version = ?
                """, context.actor(), timestamp(now), operationPlanId,
                ((Number) operation.get("version")).longValue());
        if (transitioned == 0) {
            return;
        }
        String rollbackId = "rollback-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into rollback_execution(
                    id, operation_plan_id, rollback_type, status, reason,
                    created_by, created_at, finished_at
                ) values (?, ?, 'RESET_ALL', 'DISPATCHED', ?, ?, ?, null)
                """, rollbackId, operationPlanId, "实例执行失败后自动恢复",
                context.actor(), timestamp(now));
        List<Map<String, Object>> agents = normalizeRows(jdbcTemplate.queryForList("""
                select distinct a.*
                  from rollout_instance_execution execution
                  join agent_instance a on a.instance_id = execution.instance_id
                 where execution.operation_plan_id = ?
                   and a.status = 'ACTIVE'
                   and (a.lease_expires_at is null or a.lease_expires_at > current_timestamp)
                 order by a.id
                """, operationPlanId));
        for (Map<String, Object> agent : agents) {
            enqueue(context, String.valueOf(agent.get("id")), "RESET_ALL",
                    Map.of("commandType", "RESET_ALL",
                            "operationPlanId", operationPlanId,
                            "rollbackExecutionId", rollbackId),
                    "rollback:" + operationPlanId + ":" + agent.get("id"), 10, now);
        }
        if (agents.isEmpty()) {
            jdbcTemplate.update("""
                    update rollback_execution
                       set status = 'SUCCEEDED', finished_at = ?
                     where id = ?
                    """, timestamp(now), rollbackId);
            jdbcTemplate.update("""
                    update operation_plan
                       set status = 'ROLLED_BACK', version = version + 1, updated_by = ?, updated_at = ?
                     where id = ? and status = 'ROLLING_BACK'
                    """, context.actor(), timestamp(now), operationPlanId);
        }
        Map<String, Object> current = normalizeRow(jdbcTemplate.queryForMap(
                "select * from operation_plan where id = ?", operationPlanId));
        eventWriter.recordEvent(context, "operation_plan.auto_rollback", "operation_plan",
                operationPlanId, ((Number) current.get("version")).longValue(),
                operation, current, "ROLLING_BACK", "实例执行失败，已启动自动恢复",
                Map.of("rollbackExecutionId", rollbackId,
                        "executionCount", executions.size(),
                        "commandCount", agents.size()));
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
