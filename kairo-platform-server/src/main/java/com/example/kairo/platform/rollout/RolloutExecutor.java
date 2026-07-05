package com.example.kairo.platform.rollout;

import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.persistence.mapper.RolloutExecutionMapper;
import com.example.kairo.platform.service.BusinessIdService;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class RolloutExecutor {

    private final RolloutExecutionMapper rolloutMapper;
    private final AgentCommandService commandService;
    private final PlatformCoreService eventWriter;
    private final BusinessIdService businessIdService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${kairo.platform.rollout.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Autowired
    public RolloutExecutor(RolloutExecutionMapper rolloutMapper, AgentCommandService commandService,
                           PlatformCoreService eventWriter, BusinessIdService businessIdService) {
        this(rolloutMapper, commandService, eventWriter, businessIdService, Clock.systemUTC());
    }

    RolloutExecutor(RolloutExecutionMapper rolloutMapper, AgentCommandService commandService,
                    PlatformCoreService eventWriter, BusinessIdService businessIdService, Clock clock) {
        this.rolloutMapper = rolloutMapper;
        this.commandService = commandService;
        this.eventWriter = eventWriter;
        this.businessIdService = businessIdService;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${kairo.platform.rollout.scheduler.fixed-delay-ms:3000}",
            initialDelayString = "${kairo.platform.rollout.scheduler.fixed-delay-ms:3000}")
    public void scheduledRun() {
        if (!schedulerEnabled) {
            return;
        }
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
        List<Map<String, Object>> operations = normalizeRows(rolloutMapper.runningOperations());
        for (Map<String, Object> candidate : operations) {
            operationCount++;
            RolloutProgress progress = processOperation(context, candidate);
            targetCount += progress.targets();
            commandCount += progress.commands();
        }
        return Map.of(
                "operations", operationCount,
                "targetsCaptured", targetCount,
                "commandsEnqueued", commandCount
        );
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
        return normalizeRows(rolloutMapper.executions(operationId));
    }

    private int captureTargets(RequestContext context, Map<String, Object> operation) {
        Map<String, Object> strategy = PlatformJson.readMap(String.valueOf(operation.get("strategy_json")));
        Map<String, Object> labels = selectorValueMap(strategy.get("labels"));
        List<String> requestedInstances = stringList(strategy.get("instanceIds"));
        String operationId = String.valueOf(operation.get("id"));
        List<Map<String, Object>> instances = normalizeRows(rolloutMapper.activeTargetInstances(
                operation.get("application_id"), operation.get("environment_id")));
        Instant now = clock.instant();
        int captured = 0;
        String businessName = eventWriter.rolloutBusinessName(
                String.valueOf(operation.get("resource_type")),
                String.valueOf(operation.get("resource_id")));
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
                rolloutMapper.insertTargetSnapshot(businessIdService.nextId("rollout_target", businessName),
                        operationId, instanceId, instance.get("labels_json"), timestamp(now),
                        snapshotText(instance, "nickname", instanceId),
                        snapshotText(instance, "application_name", ""),
                        snapshotText(instance, "environment_name", ""),
                        snapshotText(instance, "java_version", ""),
                        snapshotText(instance, "agent_version", ""),
                        snapshotText(instance, "load_mode", ""),
                        snapshotText(instance, "process_start_id", ""),
                        instance.get("last_seen_at"),
                        snapshotText(instance, "attach_executor_id", ""));
            } catch (DuplicateKeyException ignored) {
                // A retry reuses the immutable target snapshot.
            }
            try {
                rolloutMapper.insertExecution(businessIdService.nextId("rollout_execution", businessName),
                        operationId, instanceId,
                        ((Number) operation.get("resource_version")).longValue(),
                        context.actor(), timestamp(now),
                        snapshotText(instance, "nickname", instanceId),
                        snapshotText(instance, "application_name", ""),
                        snapshotText(instance, "environment_name", ""),
                        snapshotText(instance, "java_version", ""),
                        snapshotText(instance, "agent_version", ""),
                        snapshotText(instance, "load_mode", ""),
                        snapshotText(instance, "process_start_id", ""),
                        instance.get("last_seen_at"),
                        snapshotText(instance, "attach_executor_id", ""));
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
        List<Map<String, Object>> agents = normalizeRows(rolloutMapper.activeAgentsByInstance(instanceId));
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
        int updatedCount = rolloutMapper.markExecutionWaitingAgent(executionId, command.get("id"),
                timestamp(now), context.actor(), timestamp(now));
        if (updatedCount == 0) {
            return false;
        }
        Map<String, Object> updatedExecution = normalizeRow(rolloutMapper.execution(executionId));
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
        Map<String, Object> ruleVersion = normalizeRow(rolloutMapper.ruleVersion(ruleId, version));
        String versionStatus = String.valueOf(ruleVersion.get("status"));
        if (!"ENABLED".equals(versionStatus)) {
            throw new IllegalStateException("规则版本已停用，不能发布："
                    + ruleId + ":" + version + " status=" + versionStatus);
        }
        List<Map<String, Object>> targets = normalizeRows(rolloutMapper.firstRuleTarget(ruleVersion.get("id")));
        Map<String, Object> target = targets.isEmpty() ? Map.of() : targets.get(0);
        Map<String, Object> script = PlatformJson.readMap(String.valueOf(ruleVersion.get("script_json")));
        Map<String, Object> matcher = target.isEmpty()
                ? Map.of()
                : PlatformJson.readMap(String.valueOf(target.get("matcher_json")));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", ruleId + ":" + version);
        payload.put("version", version);
        payload.put("name", "rollout-" + ruleId);
        payload.put("description", "Kairo 平台发布规则");
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
        rolloutMapper.waitForAgent(execution.get("id"), reason, timestamp(clock.instant()));
    }

    private void failWithoutTargets(RequestContext context, Map<String, Object> operation) {
        Instant now = clock.instant();
        long currentVersion = ((Number) operation.get("version")).longValue();
        int updated = rolloutMapper.failOperationWithoutTargets(operation.get("id"),
                context.actor(), timestamp(now), currentVersion);
        if (updated == 0) {
            return;
        }
        Map<String, Object> current = normalizeRow(rolloutMapper.operation(operation.get("id")));
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
                    + "\", \"injected by Kairo\")";
        }
        return "return mock.proceed(args)";
    }

    private String optionalText(Map<String, Object> values, String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private String snapshotText(Map<String, Object> values, String key, String defaultValue) {
        Object value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? defaultValue : text;
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
