package com.example.runtimemock.platform.rollout;

import com.example.runtimemock.platform.command.AgentCommandService;
import com.example.runtimemock.platform.service.PlatformJdbcService;
import com.example.runtimemock.platform.service.PlatformJson;
import com.example.runtimemock.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "runtime-mock.platform",
        name = {"worker.enabled", "rollout.scheduler.enabled"}, havingValue = "true")
public class RolloutExecutor {

    private final JdbcTemplate jdbcTemplate;
    private final AgentCommandService commandService;
    private final PlatformJdbcService eventWriter;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public RolloutExecutor(JdbcTemplate jdbcTemplate, AgentCommandService commandService,
                           PlatformJdbcService eventWriter) {
        this(jdbcTemplate, commandService, eventWriter, Clock.systemUTC());
    }

    RolloutExecutor(JdbcTemplate jdbcTemplate, AgentCommandService commandService,
                    PlatformJdbcService eventWriter, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.commandService = commandService;
        this.eventWriter = eventWriter;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${runtime-mock.platform.rollout.scheduler.fixed-delay-ms:3000}",
            initialDelayString = "${runtime-mock.platform.rollout.scheduler.fixed-delay-ms:3000}")
    public void scheduledRun() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            runOnce(systemContext("rollout-scheduler"));
        } finally {
            running.set(false);
        }
    }

    @Transactional
    public Map<String, Object> runOnce(RequestContext context) {
        int operationCount = 0;
        int batchCount = 0;
        int commandCount = 0;
        List<Map<String, Object>> operations = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from operation_plan
                 where status = 'RUNNING'
                 order by updated_at, id
                 limit 50
                """));
        for (Map<String, Object> operation : operations) {
            operationCount++;
            RolloutProgress progress = processOperation(context, operation);
            batchCount += progress.batches();
            commandCount += progress.commands();
        }
        return Map.of(
                "operations", operationCount,
                "batchesStarted", batchCount,
                "commandsEnqueued", commandCount
        );
    }

    private RolloutProgress processOperation(RequestContext context, Map<String, Object> operation) {
        String operationId = String.valueOf(operation.get("id"));
        List<Map<String, Object>> batches = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from rollout_batch
                 where operation_plan_id = ?
                 order by batch_order, id
                """, operationId));
        if (batches.isEmpty()) {
            completeOperation(context, operation, "SUCCEEDED", Map.of("reason", "no rollout batches"));
            return new RolloutProgress(0, 0);
        }
        for (Map<String, Object> batch : batches) {
            String status = String.valueOf(batch.get("status"));
            if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
                if (automaticRollbackEnabled(operationId)) {
                    rollbackOperation(context, operation, batch);
                } else {
                    completeOperation(context, operation, "FAILED", Map.of("failedBatchId", batch.get("id")));
                }
                return new RolloutProgress(0, 0);
            }
            if (!"SUCCEEDED".equals(status)) {
                return processBatch(context, operation, batch);
            }
        }
        completeOperation(context, operation, "SUCCEEDED", Map.of("batchCount", batches.size()));
        return new RolloutProgress(0, 0);
    }

    private RolloutProgress processBatch(RequestContext context, Map<String, Object> operation,
                                         Map<String, Object> batch) {
        int batchesStarted = 0;
        int commands = 0;
        String batchId = String.valueOf(batch.get("id"));
        if ("PENDING".equals(String.valueOf(batch.get("status")))) {
            startBatch(context, operation, batch);
            batchesStarted++;
        }
        List<Map<String, Object>> executions = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from rollout_instance_execution
                 where rollout_batch_id = ?
                 order by updated_at, id
                """, batchId));
        if (executions.isEmpty()) {
            failBatch(context, batch, "No target instances matched rollout selector");
            return new RolloutProgress(batchesStarted, commands);
        }
        for (Map<String, Object> execution : executions) {
            String status = String.valueOf(execution.get("status"));
            if ("PENDING".equals(status) || ("WAITING_AGENT".equals(status) && execution.get("command_id") == null)) {
                if (dispatchExecution(context, operation, batch, execution)) {
                    commands++;
                }
            }
        }
        return new RolloutProgress(batchesStarted, commands);
    }

    private void startBatch(RequestContext context, Map<String, Object> operation, Map<String, Object> batch) {
        String batchId = String.valueOf(batch.get("id"));
        String operationId = String.valueOf(operation.get("id"));
        List<Map<String, Object>> existingExecutions = jdbcTemplate.queryForList("""
                select id from rollout_instance_execution where rollout_batch_id = ?
                """, batchId);
        if (existingExecutions.isEmpty()) {
            captureTargets(operation, batch);
        }
        Instant now = clock.instant();
        int updatedCount = jdbcTemplate.update("""
                update rollout_batch
                   set status = 'RUNNING', version = version + 1, updated_by = ?, updated_at = ?
                 where id = ? and status = 'PENDING' and version = ?
                """, context.actor(), timestamp(now), batchId, ((Number) batch.get("version")).longValue());
        if (updatedCount == 0) {
            return;
        }
        Map<String, Object> updatedBatch = normalizeRow(jdbcTemplate.queryForMap(
                "select * from rollout_batch where id = ?", batchId));
        eventWriter.recordEvent(context, "rollout_batch.start", "rollout_batch", batchId, 1,
                batch, updatedBatch, "SUCCESS", "start rollout batch",
                Map.of("operationPlanId", operationId));
    }

    private void captureTargets(Map<String, Object> operation, Map<String, Object> batch) {
        Map<String, Object> selector = PlatformJson.readMap(String.valueOf(batch.get("target_selector_json")));
        Map<String, Object> labels = selectorValueMap(selector.get("labels"));
        String operationId = String.valueOf(operation.get("id"));
        String batchId = String.valueOf(batch.get("id"));
        List<Map<String, Object>> instances = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from instance
                 where application_id = ?
                   and environment_id = ?
                   and status = 'ACTIVE'
                 order by created_at, id
                """, operation.get("application_id"), operation.get("environment_id")));
        Instant now = clock.instant();
        for (Map<String, Object> instance : instances) {
            Map<String, Object> instanceLabels = PlatformJson.readMap(String.valueOf(instance.get("labels_json")));
            if (!matchesLabels(instanceLabels, labels)) {
                continue;
            }
            String instanceId = String.valueOf(instance.get("id"));
            try {
                jdbcTemplate.update("""
                        insert into rollout_target_snapshot(
                            id, operation_plan_id, instance_id, labels_json, agent_status, captured_at
                        ) values (?, ?, ?, ?, ?, ?)
                        """, "rollout-target-" + UUID.randomUUID(), operationId, instanceId,
                        instance.get("labels_json"), "UNKNOWN", timestamp(now));
            } catch (DuplicateKeyException ignored) {
                // Existing snapshots are reused across retries.
            }
            try {
                jdbcTemplate.update("""
                        insert into rollout_instance_execution(
                            id, rollout_batch_id, instance_id, status, expected_agent_version,
                            expected_rule_version, command_id, error_message, started_at, finished_at,
                            version, updated_by, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, "rollout-execution-" + UUID.randomUUID(), batchId, instanceId,
                        "PENDING", "unknown", ((Number) operation.get("resource_version")).longValue(),
                        null, null, null, null, 1L, "rollout-scheduler", timestamp(now));
            } catch (DuplicateKeyException ignored) {
                // Manual executions or concurrent scheduler passes may already have created this row.
            }
        }
    }

    private boolean dispatchExecution(RequestContext context, Map<String, Object> operation,
                                      Map<String, Object> batch, Map<String, Object> execution) {
        String instanceId = String.valueOf(execution.get("instance_id"));
        List<Map<String, Object>> agents = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from agent_instance
                 where instance_id = ?
                   and status = 'ACTIVE'
                 order by last_heartbeat_at desc nulls last, updated_at desc, id
                 limit 1
                """, instanceId));
        if (agents.isEmpty()) {
            waitForAgent(execution, "No ACTIVE agent registered for instance " + instanceId);
            return false;
        }
        Map<String, Object> agent = agents.get(0);
        String expectedAgentVersion = String.valueOf(execution.get("expected_agent_version"));
        if (!"unknown".equalsIgnoreCase(expectedAgentVersion)
                && !expectedAgentVersion.equals(String.valueOf(agent.get("agent_version")))) {
            waitForAgent(execution, "Agent version mismatch, expected " + expectedAgentVersion
                    + " but found " + agent.get("agent_version"));
            return false;
        }
        String executionId = String.valueOf(execution.get("id"));
        String agentId = String.valueOf(agent.get("id"));
        Map<String, Object> payload = commandPayload(operation, batch, execution, agent);
        Map<String, Object> command = commandService.enqueue(context, agentId,
                String.valueOf(payload.get("commandType")), payload,
                "rollout-execution:" + executionId, 10, clock.instant());
        Instant now = clock.instant();
        int updatedCount = jdbcTemplate.update("""
                update rollout_instance_execution
                   set status = 'WAITING_AGENT',
                       command_id = ?,
                       error_message = null,
                       started_at = coalesce(started_at, ?),
                       version = version + 1,
                       updated_by = ?,
                       updated_at = ?
                 where id = ?
                   and command_id is null
                   and status in ('PENDING', 'WAITING_AGENT')
                """, command.get("id"), timestamp(now), context.actor(), timestamp(now), executionId);
        if (updatedCount == 0) {
            return false;
        }
        Map<String, Object> updatedExecution = normalizeRow(jdbcTemplate.queryForMap(
                "select * from rollout_instance_execution where id = ?", executionId));
        eventWriter.recordEvent(context, "rollout_instance_execution.dispatch",
                "rollout_instance_execution", executionId, 1, execution, updatedExecution,
                "SUCCESS", "dispatch rollout command",
                Map.of("agentId", agentId, "commandId", command.get("id")));
        return true;
    }

    private Map<String, Object> commandPayload(Map<String, Object> operation, Map<String, Object> batch,
                                               Map<String, Object> execution, Map<String, Object> agent) {
        String resourceType = String.valueOf(operation.get("resource_type"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationPlanId", operation.get("id"));
        payload.put("rolloutBatchId", batch.get("id"));
        payload.put("rolloutExecutionId", execution.get("id"));
        payload.put("agentId", agent.get("id"));
        payload.put("instanceId", execution.get("instance_id"));
        payload.put("resourceType", resourceType);
        payload.put("resourceId", operation.get("resource_id"));
        payload.put("resourceVersion", operation.get("resource_version"));
        if ("rule".equals(resourceType)) {
            payload.put("commandType", "APPLY_RULE");
            payload.put("rule", ruleCommandPayload(operation));
        } else {
            payload.put("commandType", "REFRESH_RUNTIME_STATE");
        }
        return payload;
    }

    private Map<String, Object> ruleCommandPayload(Map<String, Object> operation) {
        String ruleId = String.valueOf(operation.get("resource_id"));
        long version = ((Number) operation.get("resource_version")).longValue();
        Map<String, Object> ruleVersion = normalizeRow(jdbcTemplate.queryForMap("""
                select * from rule_version where rule_id = ? and version = ?
                """, ruleId, version));
        String versionStatus = String.valueOf(ruleVersion.get("status"));
        if (!"APPROVED".equals(versionStatus)
                && !"ACTIVE".equals(versionStatus)
                && !"PUBLISHED".equals(versionStatus)) {
            throw new IllegalStateException("Rule version is not approved for rollout: "
                    + ruleId + ":" + version + " status=" + versionStatus);
        }
        List<Map<String, Object>> targets = normalizeRows(jdbcTemplate.queryForList("""
                select * from rule_target where rule_version_id = ? order by created_at, id limit 1
                """, ruleVersion.get("id")));
        Map<String, Object> target = targets.isEmpty() ? Map.of() : targets.get(0);
        Map<String, Object> script = PlatformJson.readMap(String.valueOf(ruleVersion.get("script_json")));
        Map<String, Object> matcher = target.isEmpty()
                ? Map.of()
                : PlatformJson.readMap(String.valueOf(target.get("matcher_json")));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", ruleId + ":" + version);
        payload.put("version", version);
        payload.put("name", "rollout-" + ruleId);
        payload.put("description", "rollout command generated by platform");
        payload.put("classId", optionalText(target, "class_name", String.valueOf(target.getOrDefault("class_name", ""))));
        payload.put("className", optionalText(target, "class_name", ""));
        payload.put("classLoaderId", String.valueOf(matcher.getOrDefault("classLoaderId", "")));
        payload.put("methodName", optionalText(target, "method_name", ""));
        payload.put("methodDescriptor", String.valueOf(matcher.getOrDefault("descriptor", "")));
        payload.put("phase", String.valueOf(script.getOrDefault("phase", "BEFORE")));
        payload.put("script", scriptText(script));
        payload.put("priority", 0);
        payload.put("percentage", 100);
        payload.put("maxHits", 0);
        payload.put("expireAt", 0);
        payload.put("failOpen", true);
        payload.put("enabled", true);
        return payload;
    }

    private String scriptText(Map<String, Object> script) {
        Object value = script.get("script");
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        Object type = script.get("type");
        if ("RETURN".equals(type) && script.containsKey("value")) {
            return "return mock.returnValue(" + PlatformJson.write(script.get("value")) + ")";
        }
        if ("THROW".equals(type) && script.containsKey("exception")) {
            return "return mock.throwException(\"" + script.get("exception") + "\", \"injected by Runtime Mock\")";
        }
        return "return mock.proceed(args)";
    }

    private String optionalText(Map<String, Object> values, String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private void waitForAgent(Map<String, Object> execution, String reason) {
        jdbcTemplate.update("""
                update rollout_instance_execution
                   set status = 'WAITING_AGENT',
                       error_message = ?,
                       version = version + 1,
                       updated_by = 'rollout-scheduler',
                       updated_at = ?
                 where id = ?
                   and command_id is null
                   and status in ('PENDING', 'WAITING_AGENT')
                """, reason, timestamp(clock.instant()), execution.get("id"));
    }

    private void failBatch(RequestContext context, Map<String, Object> batch, String reason) {
        Instant now = clock.instant();
        int updatedCount = jdbcTemplate.update("""
                update rollout_batch
                   set status = 'FAILED', version = version + 1, updated_by = ?, updated_at = ?
                 where id = ? and status in ('PENDING', 'RUNNING') and version = ?
                """, context.actor(), timestamp(now), batch.get("id"),
                ((Number) batch.get("version")).longValue());
        if (updatedCount == 0) {
            return;
        }
        Map<String, Object> updated = normalizeRow(jdbcTemplate.queryForMap(
                "select * from rollout_batch where id = ?", batch.get("id")));
        eventWriter.recordEvent(context, "rollout_batch.fail", "rollout_batch", String.valueOf(batch.get("id")),
                1, batch, updated, "FAILED", reason, Map.of("reason", reason));
        Map<String, Object> operation = normalizeRow(jdbcTemplate.queryForMap(
                "select * from operation_plan where id = ?", batch.get("operation_plan_id")));
        if (automaticRollbackEnabled(String.valueOf(operation.get("id")))) {
            rollbackOperation(context, operation, updated);
        } else {
            completeOperation(context, operation, "FAILED",
                    Map.of("failedBatchId", batch.get("id"), "reason", reason));
        }
    }

    private void completeOperation(RequestContext context, Map<String, Object> operation, String status,
                                   Map<String, Object> details) {
        if (!"RUNNING".equals(String.valueOf(operation.get("status")))) {
            return;
        }
        long version = ((Number) operation.get("version")).longValue() + 1;
        Instant now = clock.instant();
        long currentVersion = ((Number) operation.get("version")).longValue();
        int updatedCount = jdbcTemplate.update("""
                update operation_plan
                   set status = ?, version = ?, updated_by = ?, updated_at = ?
                 where id = ? and status = 'RUNNING' and version = ?
                """, status, version, context.actor(), timestamp(now), operation.get("id"), currentVersion);
        if (updatedCount == 0) {
            return;
        }
        Map<String, Object> updated = normalizeRow(jdbcTemplate.queryForMap(
                "select * from operation_plan where id = ?", operation.get("id")));
        eventWriter.recordEvent(context, "operation_plan.auto_complete", "operation_plan",
                String.valueOf(operation.get("id")), version, operation, updated, status,
                "rollout executor completed operation", details);
    }

    private boolean automaticRollbackEnabled(String operationId) {
        List<String> policies = jdbcTemplate.query("""
                select rollback_policy_json from rollout_plan where operation_plan_id = ?
                """, (rs, rowNum) -> rs.getString(1), operationId);
        if (policies.isEmpty()) {
            return true;
        }
        return Boolean.parseBoolean(String.valueOf(
                PlatformJson.readMap(policies.get(0)).getOrDefault("automatic", true)));
    }

    private void rollbackOperation(RequestContext context, Map<String, Object> operation,
                                   Map<String, Object> failedBatch) {
        String operationId = String.valueOf(operation.get("id"));
        long currentVersion = ((Number) operation.get("version")).longValue();
        Instant now = clock.instant();
        int transitioned = jdbcTemplate.update("""
                update operation_plan
                   set status = 'ROLLING_BACK', version = version + 1, updated_by = ?, updated_at = ?
                 where id = ? and status = 'RUNNING' and version = ?
                """, context.actor(), timestamp(now), operationId, currentVersion);
        if (transitioned == 0) {
            return;
        }
        String rollbackId = "rollback-" + UUID.randomUUID();
        jdbcTemplate.update("""
                insert into rollback_execution(
                    id, operation_plan_id, rollback_type, status, reason, created_by, created_at, finished_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?)
                """, rollbackId, operationId, "RESET_ALL", "DISPATCHED",
                "Automatic rollback after batch " + failedBatch.get("id"),
                context.actor(), timestamp(now), null);
        List<Map<String, Object>> agents = normalizeRows(jdbcTemplate.queryForList("""
                select distinct a.*
                  from agent_instance a
                  join rollout_target_snapshot s on s.instance_id = a.instance_id
                 where s.operation_plan_id = ? and a.status = 'ACTIVE'
                """, operationId));
        for (Map<String, Object> agent : agents) {
            commandService.enqueue(context, String.valueOf(agent.get("id")), "RESET_ALL",
                    Map.of("commandType", "RESET_ALL", "operationPlanId", operationId,
                            "rollbackExecutionId", rollbackId),
                    "rollback:" + operationId + ":" + agent.get("id"), 10, now);
        }
        jdbcTemplate.update("""
                update rollback_execution
                   set status = 'SUCCEEDED', finished_at = ?
                 where id = ? and status = 'DISPATCHED'
                """, timestamp(clock.instant()), rollbackId);
        jdbcTemplate.update("""
                update operation_plan
                   set status = 'ROLLED_BACK', version = version + 1, updated_by = ?, updated_at = ?
                 where id = ? and status = 'ROLLING_BACK'
                """, context.actor(), timestamp(clock.instant()), operationId);
        Map<String, Object> updated = normalizeRow(jdbcTemplate.queryForMap(
                "select * from operation_plan where id = ?", operationId));
        eventWriter.recordEvent(context, "operation_plan.auto_rollback", "operation_plan", operationId,
                ((Number) updated.get("version")).longValue(), operation, updated, "ROLLED_BACK",
                "automatic rollback dispatched",
                Map.of("failedBatchId", failedBatch.get("id"), "agentCount", agents.size(),
                        "rollbackExecutionId", rollbackId));
    }

    private boolean matchesLabels(Map<String, Object> instanceLabels, Map<String, Object> selectorLabels) {
        for (Map.Entry<String, Object> entry : selectorLabels.entrySet()) {
            Object actual = instanceLabels.get(entry.getKey());
            if (actual == null || !String.valueOf(actual).equals(String.valueOf(entry.getValue()))) {
                return false;
            }
        }
        return true;
    }

    private Map<String, Object> selectorValueMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return PlatformJson.stringKeyMap(map);
        }
        return Map.of();
    }

    private List<Map<String, Object>> normalizeRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            normalized.add(normalizeRow(row));
        }
        return normalized;
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(), value));
        return normalized;
    }

    private RequestContext systemContext(String actor) {
        return new RequestContext(actor, "scheduler-" + clock.instant().toEpochMilli(), "127.0.0.1", "scheduler");
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record RolloutProgress(int batches, int commands) {
    }
}
