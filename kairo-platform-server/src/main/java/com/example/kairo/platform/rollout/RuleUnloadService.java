package com.example.kairo.platform.rollout;

import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.fencing.FencingTokenService;
import com.example.kairo.platform.persistence.mapper.RuleUnloadMapper;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.BusinessIdService;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.PlatformJson;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RuleUnloadService {

    private final RuleUnloadMapper unloadMapper;
    private final RbacService rbacService;
    private final FencingTokenService fencingTokenService;
    private final AgentCommandService commandService;
    private final PlatformCoreService eventWriter;
    private final BusinessIdService businessIdService;
    private final Clock clock;

    @Autowired
    public RuleUnloadService(RuleUnloadMapper unloadMapper, RbacService rbacService,
                             FencingTokenService fencingTokenService,
                             AgentCommandService commandService,
                             PlatformCoreService eventWriter,
                             BusinessIdService businessIdService) {
        this(unloadMapper, rbacService, fencingTokenService, commandService,
                eventWriter, businessIdService, Clock.systemUTC());
    }

    RuleUnloadService(RuleUnloadMapper unloadMapper, RbacService rbacService,
                      FencingTokenService fencingTokenService,
                      AgentCommandService commandService,
                      PlatformCoreService eventWriter,
                      BusinessIdService businessIdService, Clock clock) {
        this.unloadMapper = unloadMapper;
        this.rbacService = rbacService;
        this.fencingTokenService = fencingTokenService;
        this.commandService = commandService;
        this.eventWriter = eventWriter;
        this.businessIdService = businessIdService;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> unload(String operationPlanId, RequestContext context,
                                      Map<String, Object> request) {
        rbacService.require(context, "ROLLOUT_MANAGE");
        Map<String, Object> operation = operation(operationPlanId);
        String expectedStatus = requiredString(request, "expectedStatus");
        long expectedVersion = requiredLong(request, "expectedVersion");
        String currentStatus = String.valueOf(operation.get("status"));
        long currentVersion = ((Number) operation.get("version")).longValue();
        if (!currentStatus.equals(expectedStatus) || currentVersion != expectedVersion) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "发布计划状态或版本已发生变化，请刷新后重试",
                    Map.of("id", operationPlanId, "expectedStatus", expectedStatus,
                            "expectedVersion", expectedVersion, "currentStatus", currentStatus,
                            "currentVersion", currentVersion));
        }
        if (!"SUCCEEDED".equals(currentStatus) && !"FAILED".equals(currentStatus)) {
            throw PlatformException.conflict("OPERATION_PLAN_NOT_UNLOADABLE",
                    "只有已成功或失败但存在生效实例的规则计划可以执行卸载",
                    Map.of("id", operationPlanId, "status", currentStatus));
        }
        if (!"rule".equals(String.valueOf(operation.get("resource_type")))) {
            throw PlatformException.conflict("OPERATION_PLAN_NOT_UNLOADABLE",
                    "当前发布计划不是规则发布，无法清除规则字节码",
                    Map.of("id", operationPlanId, "resourceType", operation.get("resource_type")));
        }

        String reason = requiredString(request, "reason");
        String fencingToken = requiredString(request, "fencingToken");
        Map<String, Object> target = ruleTarget(operation);
        String className = String.valueOf(target.getOrDefault("class_name", ""));
        Map<String, Object> matcher = PlatformJson.readMap(String.valueOf(
                target.getOrDefault("matcher_json", "{}")));
        String classId = String.valueOf(matcher.getOrDefault("classId", className));
        if (classId.isBlank()) {
            throw PlatformException.conflict("RULE_TARGET_CLASS_MISSING",
                    "规则目标类为空，无法执行字节码卸载",
                    Map.of("operationPlanId", operationPlanId));
        }

        List<Map<String, Object>> agents = activeAgentsForSuccessfulExecutions(operationPlanId);
        if (agents.isEmpty()) {
            throw PlatformException.conflict("NO_ACTIVE_ROLLOUT_AGENT",
                    "没有找到该计划成功执行实例对应的在线 Agent，暂时无法卸载",
                    Map.of("operationPlanId", operationPlanId));
        }

        fencingTokenService.consume(context, "operation_plan", operationPlanId, fencingToken);
        Instant now = clock.instant();
        String rollbackId = businessIdService.nextId("rollback_execution",
                eventWriter.rolloutBusinessName(String.valueOf(operation.get("resource_type")),
                        String.valueOf(operation.get("resource_id"))));
        int transitioned = unloadMapper.transitionManualUnloading(operationPlanId, reason, context.actor(),
                timestamp(now), currentStatus, currentVersion);
        if (transitioned == 0) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "发布计划状态或版本已发生变化，请刷新后重试",
                    Map.of("id", operationPlanId, "expectedVersion", currentVersion));
        }
        unloadMapper.insertRollbackExecution(rollbackId, operationPlanId, "RESET_CLASS", reason,
                context.actor(), timestamp(now));

        for (Map<String, Object> agent : agents) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("commandType", "RESET_CLASS");
            payload.put("operationPlanId", operationPlanId);
            payload.put("rollbackExecutionId", rollbackId);
            payload.put("ruleId", operation.get("resource_id"));
            payload.put("ruleVersion", operation.get("resource_version"));
            payload.put("instanceId", agent.get("instance_id"));
            payload.put("classId", classId);
            payload.put("className", className);
            commandService.enqueue(context, String.valueOf(agent.get("id")), "RESET_CLASS",
                    payload, "unload:" + operationPlanId + ":" + agent.get("id"),
                    10, now);
        }

        Map<String, Object> updatedOperation = operation(operationPlanId);
        eventWriter.recordEvent(context, "operation_plan.unload", "operation_plan",
                operationPlanId, ((Number) updatedOperation.get("version")).longValue(),
                operation, updatedOperation, "UNLOADING", reason,
                Map.of("rollbackExecutionId", rollbackId, "commandCount", agents.size(),
                        "classId", classId));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operationPlan", updatedOperation);
        Map<String, Object> unloadExecution = unloadExecution(rollbackId);
        result.put("unloadExecution", unloadExecution);
        result.put("rollbackExecution", unloadExecution);
        result.put("commandCount", agents.size());
        result.put("classId", classId);
        return result;
    }

    @Transactional
    public Map<String, Object> unloadRuleForDeletion(String ruleId, Long ruleVersion,
                                                     RequestContext context) {
        rbacService.require(context, "RULE_MANAGE");
        List<Map<String, Object>> operations = normalize(unloadMapper.operationsForRule(ruleId, ruleVersion));
        Instant now = clock.instant();
        int commands = 0;
        int markedRolledBack = 0;
        List<String> affectedOperations = new ArrayList<>();
        for (Map<String, Object> operation : operations) {
            String operationPlanId = String.valueOf(operation.get("id"));
            affectedOperations.add(operationPlanId);
            boolean dispatched = false;
            if ("SUCCEEDED".equals(String.valueOf(operation.get("status")))) {
                List<Map<String, Object>> agents = activeAgentsForSuccessfulExecutions(operationPlanId);
                if (!agents.isEmpty()) {
                    Map<String, Object> target = ruleTarget(operation);
                    String className = String.valueOf(target.getOrDefault("class_name", ""));
                    Map<String, Object> matcher = PlatformJson.readMap(String.valueOf(
                            target.getOrDefault("matcher_json", "{}")));
                    String classId = String.valueOf(matcher.getOrDefault("classId", className));
                    String rollbackId = businessIdService.nextId("rollback_execution",
                            eventWriter.rolloutBusinessName(String.valueOf(operation.get("resource_type")),
                                    String.valueOf(operation.get("resource_id"))));
                    unloadMapper.insertRollbackExecution(rollbackId, operationPlanId, "RESET_CLASS",
                            "规则删除自动卸载", context.actor(), timestamp(now));
                    unloadMapper.markDeletionUnloading(operationPlanId, context.actor(), timestamp(now));
                    for (Map<String, Object> agent : agents) {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("commandType", "RESET_CLASS");
                        payload.put("operationPlanId", operationPlanId);
                        payload.put("rollbackExecutionId", rollbackId);
                        payload.put("ruleId", operation.get("resource_id"));
                        payload.put("ruleVersion", operation.get("resource_version"));
                        payload.put("instanceId", agent.get("instance_id"));
                        payload.put("classId", classId);
                        payload.put("className", className);
                        commandService.enqueue(context, String.valueOf(agent.get("id")), "RESET_CLASS",
                                payload, "delete-rule-unload:" + operationPlanId + ":" + agent.get("id"),
                                10, now);
                        commands++;
                    }
                    dispatched = true;
                }
            }
            if (!dispatched) {
                unloadMapper.markDeletionUnloadedWithoutAgents(operationPlanId, context.actor(), timestamp(now));
                unloadMapper.markExecutionsUnloaded(operationPlanId, timestamp(now), timestamp(now));
                markedRolledBack++;
            }
        }
        return Map.of(
                "ruleId", ruleId,
                "ruleVersion", ruleVersion == null ? "" : ruleVersion,
                "operationCount", operations.size(),
                "commandsDispatched", commands,
                "markedRolledBack", markedRolledBack,
                "operationIds", affectedOperations
        );
    }

    private Map<String, Object> ruleTarget(Map<String, Object> operation) {
        List<Map<String, Object>> rows = normalize(unloadMapper.ruleTarget(
                operation.get("resource_id"), operation.get("resource_version")));
        if (rows.isEmpty()) {
            throw PlatformException.conflict("RULE_TARGET_NOT_FOUND",
                    "未找到该发布版本对应的规则目标，无法执行卸载",
                    Map.of("ruleId", operation.get("resource_id"),
                            "ruleVersion", operation.get("resource_version")));
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> activeAgentsForSuccessfulExecutions(String operationPlanId) {
        return normalize(unloadMapper.activeAgentsForSuccessfulExecutions(operationPlanId));
    }

    private Map<String, Object> operation(String id) {
        Map<String, Object> operation = unloadMapper.operation(id);
        if (operation == null) {
            throw PlatformException.notFound("operation_plan", id);
        }
        return normalizeOne(operation);
    }

    private Map<String, Object> unloadExecution(String id) {
        return normalizeOne(unloadMapper.rollbackExecution(id));
    }

    private String requiredString(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "缺少必填字段：" + key);
        }
        return String.valueOf(value);
    }

    private long requiredLong(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "缺少必填字段：" + key);
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException exception) {
            throw PlatformException.badRequest("INVALID_FIELD", "字段必须是整数：" + key);
        }
    }

    private List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        return rows.stream().map(this::normalizeOne).toList();
    }

    private Map<String, Object> normalizeOne(Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        row.forEach((key, value) -> normalized.put(key.toLowerCase(), value));
        return normalized;
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
