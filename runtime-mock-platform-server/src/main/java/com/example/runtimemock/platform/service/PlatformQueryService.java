package com.example.runtimemock.platform.service;

import com.example.runtimemock.platform.persistence.mapper.RuleLedgerQueryMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public final class PlatformQueryService {

    private static final String CURRENT_INSTANCE_TABLE = """
            (select *
               from instance
              where status not in ('OFFLINE', 'STOPPED', 'ARCHIVED')) instance
            """;

    private static final Map<String, ResourceDefinition> RESOURCES = Map.ofEntries(
            resource("applications", "application", "created_at desc, id", "id", "name", "project_id"),
            selectedResource("rollout-environments",
                    """
                    (
                        select lower(coalesce(e.type, e.name)) as id,
                               lower(coalesce(e.type, e.name)) as name,
                               lower(coalesce(e.type, e.name)) as type,
                               max(r.updated_at) as updated_at
                          from environment e
                          join rule r on r.environment_id = e.id
                          join rule_version rv on rv.rule_id = r.id
                         where rv.status = 'ENABLED'
                         group by lower(coalesce(e.type, e.name))
                    ) rollout_environment
                    """,
                    "id, name, type, updated_at",
                    """
                    case type
                        when 'dev' then 1
                        when 'sit' then 2
                        when 'uat' then 3
                        when 'prod' then 4
                        else 99
                    end, id
                    """,
                    "id", "name", "type"),
            selectedResource("rollout-applications",
                    """
                    (
                        select a.id,
                               a.name,
                               a.project_id,
                               e.id as environment_id,
                               lower(coalesce(e.type, e.name)) as environment_key,
                               max(r.updated_at) as updated_at
                          from application a
                          join rule r on r.application_id = a.id
                          join environment e on e.id = r.environment_id
                          join rule_version rv on rv.rule_id = r.id
                         where rv.status = 'ENABLED'
                         group by a.id, a.name, a.project_id, e.id, lower(coalesce(e.type, e.name))
                    ) rollout_application
                    """,
                    "id, name, project_id, environment_id, environment_key, updated_at",
                    "updated_at desc, id, environment_id", "id", "name", "project_id", "environment_key"),
            resource("environments", "environment", "created_at desc, id", "id", "name", "type", "application_id"),
            selectedResource("instances", CURRENT_INSTANCE_TABLE,
                    """
                    id, nickname, hostname, process_id, runtime, status, application_id, environment_id,
                    (select a.name from application a where a.id = instance.application_id) as application_name,
                    (select p.name from project p
                      join application a on a.project_id = p.id
                     where a.id = instance.application_id) as project_name,
                    (select lower(coalesce(e.type, e.name)) from environment e where e.id = instance.environment_id) as environment_name,
                    coalesce(
                        (select ai.status
                           from agent_instance ai
                          where ai.instance_id = instance.id
                          order by ai.last_heartbeat_at desc nulls last, ai.updated_at desc, ai.id
                          limit 1),
                        (select aet.status
                           from attach_executor_target aet
                           join attach_executor ae on ae.id = aet.executor_id
                          where aet.instance_id = instance.id
                          order by aet.last_seen_at desc nulls last, ae.last_heartbeat_at desc nulls last,
                                   aet.updated_at desc, ae.updated_at desc, aet.executor_id
                          limit 1)
                    ) as agent_status,
                    labels_json, last_seen_at, created_at, updated_at, process_start_id,
                    jvm_started_at, java_version, load_mode, agent_version, capabilities_json,
                    lease_expires_at, registration_status
                    """,
                    "updated_at desc, id", "id", "nickname", "hostname",
                    "(select a.name from application a where a.id = instance.application_id)",
                    "(select lower(coalesce(e.type, e.name)) from environment e where e.id = instance.environment_id)",
                    "application_id", "environment_id", "status"),
            resource("sidecars", "sidecar_instance", "updated_at desc, id", "id", "endpoint", "status", "sidecar_version"),
            resource("attach-executors", "attach_executor", "updated_at desc, id",
                    "id", "executor_type", "hostname", "endpoint", "status", "executor_version"),
            selectedResource("attach-targets", "attach_executor_target",
                    "executor_id, instance_id, process_id, agent_jar, runtime, java_version, status, last_seen_at, created_at, updated_at",
                    "updated_at desc, executor_id, instance_id", "executor_id", "instance_id", "process_id", "status"),
            selectedResource("attach-executor-commands", "attach_executor_command",
                    """
                    id, executor_id, instance_id, command_type, status, process_id,
                    agent_jar, attempt, max_attempts, lease_owner, lease_expires_at,
                    error_message, created_at, updated_at, started_at, finished_at
                    """,
                    "updated_at desc, id", "id", "executor_id", "instance_id", "command_type", "status"),
            selectedResource("agents", "agent_instance",
                    "id, instance_id, sidecar_id, status, agent_version, bootstrap_version, listen_host, listen_port, capabilities_json, last_heartbeat_at, created_at, updated_at",
                    "updated_at desc, id", "id", "listen_host", "status", "agent_version"),
            resource("agent-commands", "agent_command", "updated_at desc, id", "id", "agent_id", "command_type", "status"),
            resource("rules", "rule", "updated_at desc, id", "id", "name", "application_id", "environment_id"),
            selectedResource("rule-versions",
                    """
                    (
                        select *
                          from rule_version
                         where status = 'ENABLED'
                    ) rule_version
                    """,
                    "*", "created_at desc, id", "id", "rule_id", "status", "risk_level"),
            resource("operation-plans", "operation_plan", "updated_at desc, id", "id", "resource_type", "resource_id", "status"),
            selectedResource("rollout-executions", "rollout_instance_execution",
                    """
                    id, operation_plan_id, instance_id, status, expected_agent_version,
                    expected_rule_version, command_id, error_message, started_at, finished_at, updated_at,
                    instance_nickname, application_name, environment_name, java_version,
                    agent_version, load_mode, process_start_id, instance_last_seen_at,
                    attach_executor_id
                    """,
                    "updated_at desc, id", "id", "operation_plan_id", "instance_id",
                    "instance_nickname", "application_name", "environment_name", "status"),
            selectedResource("rollout-targets", "rollout_target_snapshot",
                    """
                    id, operation_plan_id, instance_id, labels_json, agent_status, captured_at,
                    instance_nickname, application_name, environment_name, java_version,
                    agent_version, load_mode, process_start_id, instance_last_seen_at,
                    attach_executor_id
                    """,
                    "captured_at desc, id", "id", "operation_plan_id", "instance_id",
                    "instance_nickname", "application_name", "environment_name", "agent_status"),
            resource("rollback-executions", "rollback_execution", "created_at desc, id",
                    "id", "operation_plan_id", "rollback_type", "status", "reason"),
            selectedResource("tokens",
                    """
                    (
                        select id, subject_type, subject_id,
                               case
                                   when status = 'ACTIVE' and (expires_at is null or expires_at > current_timestamp) then 'VALID'
                                   else 'INVALID'
                               end as status,
                               created_by, created_at, expires_at, last_used_at, revoked_at
                          from platform_access_token
                    ) platform_access_token
                    """,
                    "id, subject_type, subject_id, status, created_by, created_at, expires_at, last_used_at, revoked_at",
                    "created_at desc, id", "id", "subject_id", "status")
    );

    private final JdbcTemplate jdbcTemplate;
    private final RuleLedgerQueryMapper ruleLedgerQueryMapper;

    public PlatformQueryService(JdbcTemplate jdbcTemplate, RuleLedgerQueryMapper ruleLedgerQueryMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.ruleLedgerQueryMapper = ruleLedgerQueryMapper;
    }

    public Map<String, Object> page(String resource, int requestedPage, int requestedSize, String query) {
        int page = Math.max(0, requestedPage);
        int size = Math.min(200, Math.max(1, requestedSize));
        String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if ("rules".equals(resource)) {
            List<Map<String, Object>> items = normalize(ruleLedgerQueryMapper.pageRules(size, page * size, search));
            long total = ruleLedgerQueryMapper.countRules(search);
            return Map.of("items", items, "page", page, "size", size, "total", total);
        }

        ResourceDefinition definition = definition(resource);
        String where = "";
        Object[] args;
        if (search.isEmpty()) {
            args = new Object[]{size, page * size};
        } else {
            String expression = definition.searchColumns().stream()
                    .map(column -> "lower(coalesce(" + column + ", '')) like ?")
                    .reduce((left, right) -> left + " or " + right)
                    .orElse("1 = 0");
            where = " where " + expression;
            args = new Object[definition.searchColumns().size() + 2];
            for (int index = 0; index < definition.searchColumns().size(); index++) {
                args[index] = "%" + search + "%";
            }
            args[args.length - 2] = size;
            args[args.length - 1] = page * size;
        }
        List<Map<String, Object>> items = normalize(jdbcTemplate.queryForList(
                "select " + definition.selectColumns() + " from " + definition.table() + where
                        + " order by " + definition.orderBy() + " limit ? offset ?",
                args));
        Object[] countArgs = search.isEmpty()
                ? new Object[0]
                : java.util.Collections.nCopies(definition.searchColumns().size(), "%" + search + "%").toArray();
        Long total = jdbcTemplate.queryForObject(
                "select count(*) from " + definition.table() + where, Long.class, countArgs);
        return Map.of(
                "items", items,
                "page", page,
                "size", size,
                "total", total == null ? 0 : total
        );
    }

    public Map<String, Object> detail(String resource, String id) {
        if ("rules".equals(resource)) {
            Map<String, Object> rule = ruleLedgerQueryMapper.ruleDetail(id);
            if (rule == null) {
                throw PlatformException.notFound(resource, id);
            }
            Map<String, Object> result = new LinkedHashMap<>(normalizeRow(rule));
            result.put("allowed_actions", allowedActions(resource, String.valueOf(result.getOrDefault("status", ""))));
            return result;
        }

        ResourceDefinition definition = definition(resource);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select " + definition.selectColumns() + " from " + definition.table() + " where id = ?", id);
        if (rows.isEmpty()) {
            throw PlatformException.notFound(resource, id);
        }
        Map<String, Object> result = new LinkedHashMap<>(normalizeRow(rows.get(0)));
        if ("instances".equals(resource)) {
            enrichInstanceAgent(result, id);
        }
        if ("tokens".equals(resource)) {
            Map<String, Object> tokenDetail = new LinkedHashMap<>();
            tokenDetail.put("id", result.get("id"));
            tokenDetail.put("subject_id", result.get("subject_id"));
            tokenDetail.put("expires_at", result.get("expires_at"));
            tokenDetail.put("allowed_actions", List.of());
            return tokenDetail;
        }
        result.put("allowed_actions", allowedActions(resource, String.valueOf(result.getOrDefault("status", ""))));
        return result;
    }

    private void enrichInstanceAgent(Map<String, Object> result, String instanceId) {
        List<Map<String, Object>> agents = normalize(jdbcTemplate.queryForList("""
                select id, instance_id, sidecar_id, status, agent_version, bootstrap_version,
                       listen_host, listen_port, capabilities_json, last_heartbeat_at,
                       lease_expires_at, created_at, updated_at
                  from agent_instance
                 where instance_id = ?
                 order by last_heartbeat_at desc nulls last, updated_at desc, id
                 limit 1
                """, instanceId));
        result.put("agent", agents.isEmpty() ? null : agents.get(0));
        List<Map<String, Object>> executors = normalize(jdbcTemplate.queryForList("""
                select ae.id,
                       ae.executor_type,
                       ae.hostname,
                       ae.endpoint,
                       ae.status,
                       ae.executor_version,
                       aet.process_id,
                       aet.agent_jar,
                       aet.status as target_status,
                       aet.last_seen_at
                  from attach_executor_target aet
                  join attach_executor ae on ae.id = aet.executor_id
                 where aet.instance_id = ?
                 order by ae.last_heartbeat_at desc nulls last, ae.updated_at desc, ae.id
                 limit 1
                """, instanceId));
        result.put("attach_executor", executors.isEmpty() ? null : executors.get(0));
    }

    public Map<String, Object> ruleDetail(String id) {
        Map<String, Object> rule = detail("rules", id);
        List<Map<String, Object>> versions = normalize(ruleLedgerQueryMapper.ruleVersions(id));
        List<Map<String, Object>> targets = normalize(ruleLedgerQueryMapper.ruleTargets(id));
        List<Map<String, Object>> capabilities = normalize(ruleLedgerQueryMapper.ruleCapabilities(id));
        return Map.of("rule", rule, "versions", versions, "targets", targets, "capabilities", capabilities);
    }

    public Map<String, Object> dashboard() {
        List<Map<String, Object>> recentAudits = normalize(jdbcTemplate.queryForList(
                "select * from audit_record order by sequence desc limit 8"));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("agentsTotal", count("agent_instance"));
        counts.put("agentsOnline", countWhere("agent_instance", "status in ('ACTIVE', 'ONLINE')"));
        counts.put("instancesTotal", count("instance"));
        counts.put("injectableInstancesOnline", countQuery("""
                select count(*)
                  from instance i
                 where i.status in ('ACTIVE', 'ONLINE')
                   and (
                       exists (
                           select 1
                             from agent_instance a
                            where a.instance_id = i.id
                              and a.status in ('ACTIVE', 'ONLINE')
                              and (a.lease_expires_at is null or a.lease_expires_at > now())
                       )
                       or exists (
                           select 1
                             from attach_executor_target t
                             join attach_executor e on e.id = t.executor_id
                            where t.instance_id = i.id
                              and t.status in ('ACTIVE', 'ONLINE')
                              and e.status in ('ACTIVE', 'ONLINE')
                              and (e.lease_expires_at is null or e.lease_expires_at > now())
                       )
                   )
                """));
        counts.put("rulesTotal", count("rule"));
        counts.put("rulesActive", countWhere("rule", "status = 'ENABLED'"));
        counts.put("rolloutsRunning", countWhere("operation_plan", "status = 'RUNNING'"));
        Map<String, Long> trendCounts = new TreeMap<>();
        normalize(jdbcTemplate.queryForList("select * from audit_record")).forEach(row -> {
            String label = String.valueOf(row.getOrDefault("result", "UNKNOWN"));
            trendCounts.merge(label, 1L, Long::sum);
        });
        List<Map<String, Object>> trends = trendCounts.entrySet().stream()
                .map(entry -> Map.<String, Object>of("label", entry.getKey(), "value", entry.getValue()))
                .toList();
        return Map.of(
                "checkedAt", Instant.now().toString(),
                "counts", counts,
                "auditTrends", trends,
                "recentAudits", recentAudits
        );
    }

    public List<Map<String, Object>> searchTargets(String query, String applicationId, String environmentId) {
        String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String application = applicationId == null ? "" : applicationId.trim();
        String environment = environmentId == null ? "" : environmentId.trim();
        StringBuilder sql = new StringBuilder("""
                select rt.class_name, rt.method_name, rt.protocol, count(*) as version_count
                  from rule_target rt
                  join rule_version rv on rv.id = rt.rule_version_id
                  join rule r on r.id = rv.rule_id
                 where rv.status = 'ENABLED'
                   and (lower(rt.class_name) like ? or lower(rt.method_name) like ?)
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add("%" + search + "%");
        args.add("%" + search + "%");
        if (!application.isEmpty()) {
            sql.append(" and r.application_id = ?");
            args.add(application);
        }
        if (!environment.isEmpty()) {
            sql.append(" and r.environment_id = ?");
            args.add(environment);
        }
        sql.append("""
                 group by rt.class_name, rt.method_name, rt.protocol
                 order by rt.class_name, rt.method_name
                 limit 100
                """);
        return normalize(jdbcTemplate.queryForList(sql.toString(), args.toArray()));
    }

    private List<String> allowedActions(String resource, String status) {
        return switch (resource) {
            case "operation-plans" -> switch (status) {
                case "DRAFT" -> List.of("RUNNING");
                case "SUCCEEDED" -> List.of("UNLOAD");
                case "FAILED" -> List.of("UNLOAD");
                default -> List.of();
            };
            case "rollout-executions" -> "SUCCEEDED".equals(status)
                    ? List.of("UNLOAD_PLAN")
                    : List.of();
            default -> List.of();
        };
    }

    private ResourceDefinition definition(String resource) {
        ResourceDefinition definition = RESOURCES.get(resource);
        if (definition == null) {
            throw PlatformException.notFound("query-resource", resource);
        }
        return definition;
    }

    private long count(String table) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + table, Long.class);
        return value == null ? 0 : value;
    }

    private long countWhere(String table, String where) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + table + " where " + where, Long.class);
        return value == null ? 0 : value;
    }

    private long countQuery(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        return rows.stream().map(this::normalizeRow).toList();
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

    private static Map.Entry<String, ResourceDefinition> resource(
            String name, String table, String orderBy, String... searchColumns) {
        return Map.entry(name, new ResourceDefinition(table, "*", orderBy, List.of(searchColumns)));
    }

    private static Map.Entry<String, ResourceDefinition> selectedResource(
            String name, String table, String selectColumns, String orderBy, String... searchColumns) {
        return Map.entry(name, new ResourceDefinition(table, selectColumns, orderBy, List.of(searchColumns)));
    }

    private record ResourceDefinition(String table, String selectColumns, String orderBy,
                                      List<String> searchColumns) {
    }
}
