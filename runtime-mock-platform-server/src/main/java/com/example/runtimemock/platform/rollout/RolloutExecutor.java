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
        int targetCount = 0;
        int commandCount = 0;
        List<Map<String, Object>> operations = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from operation_plan
                 where status in ('APPROVED', 'RUNNING')
                 order by updated_at, id
                 limit 50
                """));
        for (Map<String, Object> candidate : operations) {
            Map<String, Object> operation = startApprovedOperation(context, candidate);
            if (operation == null) {
                continue;
            }
            operationCount++;
            RolloutProgress progress = processOperation(context, operation);
            targetCount += progress.targets();
            commandCount += progress.commands();
        }
        return Map.of(
                "operations", operationCount,
                "targetsCaptured", targetCount,
                "commandsEnqueued", commandCount
        );
    }

    private Map<String, Object> startApprovedOperation(RequestContext context,
                                                       Map<String, Object> operation) {
        if (!"APPROVED".equals(String.valueOf(operation.get("status")))) {
            return operation;
        }
        Instant now = clock.instant();
        long currentVersion = ((Number) operation.get("version")).longValue();
        int updated = jdbcTemplate.update("""
                update operation_plan
                   set status = 'RUNNING',
                       version = version + 1,
                       updated_by = ?,
                       updated_at = ?
                 where id = ?
                   and status = 'APPROVED'
                   and version = ?
                """, context.actor(), timestamp(now), operation.get("id"), currentVersion);
        if (updated == 0) {
            return null;
        }
        Map<String, Object> runningOperation = normalizeRow(jdbcTemplate.queryForMap(
                "select * from operation_plan where id = ?", operation.get("id")));
        eventWriter.recordEvent(context, "operation_plan.execution_started", "operation_plan",
                String.valueOf(operation.get("id")),
                ((Number) runningOperation.get("version")).longValue(),
                operation, runningOperation, "SUCCESS", "已开始向目标实例发布规则",
                Map.of("targetMode", "ALL_ACTIVE_INSTANCES"));
        return runningOperation;
    }

    private RolloutProgress processOperation(RequestContext context, Map<String, Object> operation) {
        String operationId = String.valueOf(operation.get("id"));
        List<Map<String, Object>> executions = executions(operationId);
        int targets = 0;
        if (executions.isEmpty()) {
            targets = captureTargets(context, operation);
            executions = executions(operationId);
        }
        if (executions.isEmpty()) {
            failWithoutTargets(context, operation);
            return new RolloutProgress(targets, 0);
        }

        int commands = 0;
        for (Map<String, Object> execution : executions) {
            String status = String.valueOf(execution.get("status"));
            if ("PENDING".equals(status)
                    || ("WAITING_AGENT".equals(status) && execution.get("command_id") == null)) {
                if (dispatchExecution(context, operation, execution)) {
                    commands++;
                }
            }
        }
        return new RolloutProgress(targets, commands);
    }

    private List<Map<String, Object>> executions(String operationId) {
        return normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from rollout_instance_execution
                 where operation_plan_id = ?
                 order by updated_at, id
                """, operationId));
    }

    private int captureTargets(RequestContext context, Map<String, Object> operation) {
        Map<String, Object> strategy = PlatformJson.readMap(String.valueOf(operation.get("strategy_json")));
        Map<String, Object> labels = selectorValueMap(strategy.get("labels"));
        List<String> requestedInstances = stringList(strategy.get("instanceIds"));
        String operationId = String.valueOf(operation.get("id"));
        List<Map<String, Object>> instances = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from instance
                 where application_id = ?
                   and environment_id = ?
                   and status = 'ACTIVE'
                   and registration_status = 'ASSIGNED'
                   and (lease_expires_at is null or lease_expires_at > current_timestamp)
                 order by created_at, id
                """, operation.get("application_id"), operation.get("environment_id")));
        Instant now = clock.instant();
        int captured = 0;
        for (Map<String, Object> instance : instances) {
            String instanceId = String.valueOf(instance.get("id"));
            if (!requestedInstances.isEmpty() && !requestedInstances.contains(instanceId)) {
                continue;
            }
            Map<String, Object> instanceLabels =
                    PlatformJson.readMap(String.valueOf(instance.get("labels_json")));
            if (!matchesLabels(instanceLabels, labels)) {
                continue;
            }
            try {
                jdbcTemplate.update("""
                        insert into rollout_target_snapshot(
                            id, operation_plan_id, instance_id, labels_json, agent_status, captured_at
                        ) values (?, ?, ?, ?, ?, ?)
                        """, "rollout-target-" + UUID.randomUUID(), operationId, instanceId,
                        instance.get("labels_json"), "UNKNOWN", timestamp(now));
            } catch (DuplicateKeyException ignored) {
                // A retry reuses the immutable target snapshot.
            }
            try {
                jdbcTemplate.update("""
                        insert into rollout_instance_execution(
                            id, rollout_batch_id, operation_plan_id, instance_id, status,
                            expected_agent_version, expected_rule_version, command_id, error_message,
                            started_at, finished_at, version, updated_by, updated_at
                        ) values (?, null, ?, ?, 'PENDING', 'unknown', ?, null, null, null, null, 1, ?, ?)
                        """, "rollout-execution-" + UUID.randomUUID(), operationId, instanceId,
                        ((Number) operation.get("resource_version")).longValue(),
                        context.actor(), timestamp(now));
                captured++;
            } catch (DuplicateKeyException ignored) {
                // Concurrent scheduler passes may already have created the execution.
            }
        }
        if (captured > 0) {
            eventWriter.recordEvent(context, "operation_plan.targets_captured", "operation_plan",
                    operationId, ((Number) operation.get("version")).longValue(),
                    Map.of(), Map.of("targetCount", captured), "SUCCESS",
                    "已捕获发布目标实例", Map.of("targetCount", captured));
        }
        return captured;
    }

    private boolean dispatchExecution(RequestContext context, Map<String, Object> operation,
                                      Map<String, Object> execution) {
        String instanceId = String.valueOf(execution.get("instance_id"));
        List<Map<String, Object>> agents = normalizeRows(jdbcTemplate.queryForList("""
                select *
                  from agent_instance
                 where instance_id = ?
                   and status = 'ACTIVE'
                   and (lease_expires_at is null or lease_expires_at > current_timestamp)
                 order by last_heartbeat_at desc nulls last, updated_at desc, id
                 limit 1
                """, instanceId));
        if (agents.isEmpty()) {
            waitForAgent(execution, "实例 " + instanceId + " 没有在线 Agent");
            return false;
        }
        Map<String, Object> agent = agents.get(0);
        String expectedAgentVersion = String.valueOf(execution.get("expected_agent_version"));
        if (!"unknown".equalsIgnoreCase(expectedAgentVersion)
                && !expectedAgentVersion.equals(String.valueOf(agent.get("agent_version")))) {
            waitForAgent(execution, "Agent 版本不匹配，期望 " + expectedAgentVersion
                    + "，实际 " + agent.get("agent_version"));
            return false;
        }

        String executionId = String.valueOf(execution.get("id"));
        String agentId = String.valueOf(agent.get("id"));
        Map<String, Object> payload = commandPayload(operation, execution, agent);
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
                "SUCCESS", "已发送规则发布命令",
                Map.of("agentId", agentId, "commandId", command.get("id")));
        return true;
    }

    private Map<String, Object> commandPayload(Map<String, Object> operation,
                                               Map<String, Object> execution,
                                               Map<String, Object> agent) {
        String resourceType = String.valueOf(operation.get("resource_type"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operationPlanId", operation.get("id"));
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
            throw new IllegalStateException("规则版本尚未获批，不能发布："
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
        payload.put("description", "Runtime Mock 平台发布规则");
        payload.put("classId", String.valueOf(matcher.getOrDefault(
                "classId", target.getOrDefault("class_name", ""))));
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

    private void failWithoutTargets(RequestContext context, Map<String, Object> operation) {
        Instant now = clock.instant();
        long currentVersion = ((Number) operation.get("version")).longValue();
        int updated = jdbcTemplate.update("""
                update operation_plan
                   set status = 'FAILED', version = version + 1, updated_by = ?, updated_at = ?
                 where id = ? and status = 'RUNNING' and version = ?
                """, context.actor(), timestamp(now), operation.get("id"), currentVersion);
        if (updated == 0) {
            return;
        }
        Map<String, Object> current = normalizeRow(jdbcTemplate.queryForMap(
                "select * from operation_plan where id = ?", operation.get("id")));
        eventWriter.recordEvent(context, "operation_plan.no_targets", "operation_plan",
                String.valueOf(operation.get("id")), ((Number) current.get("version")).longValue(),
                operation, current, "FAILED", "没有匹配到在线且已分配的目标实例",
                Map.of("applicationId", operation.get("application_id"),
                        "environmentId", operation.get("environment_id")));
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
            return "return mock.throwException(\"" + script.get("exception")
                    + "\", \"injected by Runtime Mock\")";
        }
        return "return mock.proceed(args)";
    }

    private String optionalText(Map<String, Object> values, String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : String.valueOf(value);
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

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
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
        return new RequestContext(actor, "scheduler-" + clock.instant().toEpochMilli(),
                "127.0.0.1", "scheduler");
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }

    private record RolloutProgress(int targets, int commands) {
    }
}
