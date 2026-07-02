package com.example.runtimemock.platform.rollout;

import com.example.runtimemock.platform.command.AgentCommandService;
import com.example.runtimemock.platform.fencing.FencingTokenService;
import com.example.runtimemock.platform.service.PlatformException;
import com.example.runtimemock.platform.service.PlatformJdbcService;
import com.example.runtimemock.platform.service.PlatformJson;
import com.example.runtimemock.platform.service.RbacService;
import com.example.runtimemock.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RuleUnloadService {

    private final JdbcTemplate jdbcTemplate;
    private final RbacService rbacService;
    private final FencingTokenService fencingTokenService;
    private final AgentCommandService commandService;
    private final PlatformJdbcService eventWriter;
    private final Clock clock;

    @Autowired
    public RuleUnloadService(JdbcTemplate jdbcTemplate, RbacService rbacService,
                             FencingTokenService fencingTokenService,
                             AgentCommandService commandService,
                             PlatformJdbcService eventWriter) {
        this(jdbcTemplate, rbacService, fencingTokenService, commandService,
                eventWriter, Clock.systemUTC());
    }

    RuleUnloadService(JdbcTemplate jdbcTemplate, RbacService rbacService,
                      FencingTokenService fencingTokenService,
                      AgentCommandService commandService,
                      PlatformJdbcService eventWriter, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.rbacService = rbacService;
        this.fencingTokenService = fencingTokenService;
        this.commandService = commandService;
        this.eventWriter = eventWriter;
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
        String rollbackId = "rollback-" + UUID.randomUUID();
        int transitioned = jdbcTemplate.update("""
                update operation_plan
                   set status = 'UNLOADING', version = version + 1, updated_by = ?, updated_at = ?
                 where id = ? and status = ? and version = ?
                """, context.actor(), timestamp(now), operationPlanId, currentStatus, currentVersion);
        if (transitioned == 0) {
            throw PlatformException.conflict("RESOURCE_VERSION_CONFLICT",
                    "发布计划状态或版本已发生变化，请刷新后重试",
                    Map.of("id", operationPlanId, "expectedVersion", currentVersion));
        }
        jdbcTemplate.update("""
                insert into rollback_execution(
                    id, operation_plan_id, rollback_type, status, reason,
                    created_by, created_at, finished_at
                ) values (?, ?, 'RESET_CLASS', 'DISPATCHED', ?, ?, ?, null)
                """, rollbackId, operationPlanId, reason, context.actor(), timestamp(now));

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
        String versionClause = ruleVersion == null ? "" : " and resource_version = ?";
        Object[] args = ruleVersion == null
                ? new Object[]{ruleId}
                : new Object[]{ruleId, ruleVersion};
        List<Map<String, Object>> operations = normalize(jdbcTemplate.queryForList("""
                select *
                 from operation_plan
                 where resource_type = 'rule'
                   and resource_id = ?
                   %s
                   and status <> 'UNLOADED'
                 order by updated_at desc, id
                """.formatted(versionClause), args));
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
                    String rollbackId = "rollback-" + UUID.randomUUID();
                    jdbcTemplate.update("""
                            insert into rollback_execution(
                                id, operation_plan_id, rollback_type, status, reason,
                                created_by, created_at, finished_at
                            ) values (?, ?, 'RESET_CLASS', 'DISPATCHED', ?, ?, ?, null)
                            """, rollbackId, operationPlanId, "规则删除自动卸载", context.actor(), timestamp(now));
                    jdbcTemplate.update("""
                            update operation_plan
                               set status = 'UNLOADING',
                                   version = version + 1,
                                   updated_by = ?,
                                   updated_at = ?
                             where id = ?
                            """, context.actor(), timestamp(now), operationPlanId);
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
                jdbcTemplate.update("""
                        update operation_plan
                           set status = 'UNLOADED',
                               version = version + 1,
                               updated_by = ?,
                               updated_at = ?
                         where id = ?
                        """, context.actor(), timestamp(now), operationPlanId);
                jdbcTemplate.update("""
                        update rollout_instance_execution
                           set status = 'UNLOADED',
                               finished_at = coalesce(finished_at, ?),
                               updated_at = ?
                         where operation_plan_id = ?
                           and status <> 'UNLOADED'
                        """, timestamp(now), timestamp(now), operationPlanId);
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
        List<Map<String, Object>> rows = normalize(jdbcTemplate.queryForList("""
                select rt.*
                  from rule_target rt
                  join rule_version rv on rv.id = rt.rule_version_id
                 where rv.rule_id = ? and rv.version = ?
                 order by rt.created_at, rt.id
                 limit 1
                """, operation.get("resource_id"), operation.get("resource_version")));
        if (rows.isEmpty()) {
            throw PlatformException.conflict("RULE_TARGET_NOT_FOUND",
                    "未找到该发布版本对应的规则目标，无法执行卸载",
                    Map.of("ruleId", operation.get("resource_id"),
                            "ruleVersion", operation.get("resource_version")));
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> activeAgentsForSuccessfulExecutions(String operationPlanId) {
        return normalize(jdbcTemplate.queryForList("""
                select distinct a.*
                  from rollout_instance_execution rie
                  join agent_instance a on a.instance_id = rie.instance_id
                 where rie.operation_plan_id = ?
                   and rie.status = 'SUCCEEDED'
                   and a.status = 'ACTIVE'
                   and (a.lease_expires_at is null or a.lease_expires_at > current_timestamp)
                 order by a.id
                """, operationPlanId));
    }

    private Map<String, Object> operation(String id) {
        List<Map<String, Object>> rows = normalize(
                jdbcTemplate.queryForList("select * from operation_plan where id = ?", id));
        if (rows.isEmpty()) {
            throw PlatformException.notFound("operation_plan", id);
        }
        return rows.get(0);
    }

    private Map<String, Object> unloadExecution(String id) {
        return normalize(jdbcTemplate.queryForList(
                "select * from rollback_execution where id = ?", id)).get(0);
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
        return rows.stream().map(row -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach((key, value) -> normalized.put(key.toLowerCase(), value));
            return normalized;
        }).toList();
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
