package com.example.runtimemock.platform.service;

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

    private static final Map<String, ResourceDefinition> RESOURCES = Map.ofEntries(
            resource("applications", "application", "created_at desc, id", "id", "name", "project_id"),
            resource("environments", "environment", "created_at desc, id", "id", "name", "type", "application_id"),
            selectedResource("instances", "instance",
                    """
                    id, hostname, process_id, runtime, status, application_id, environment_id,
                    (select a.name from application a where a.id = instance.application_id) as application_name,
                    (select p.name from project p
                      join application a on a.project_id = p.id
                     where a.id = instance.application_id) as project_name,
                    (select e.name from environment e where e.id = instance.environment_id) as environment_name,
                    labels_json, last_seen_at, created_at, updated_at, process_start_id,
                    jvm_started_at, java_version, load_mode, agent_version, capabilities_json,
                    lease_expires_at, registration_status
                    """,
                    "updated_at desc, id", "id", "hostname", "application_id", "environment_id",
                    "status"),
            resource("sidecars", "sidecar_instance", "updated_at desc, id", "id", "endpoint", "status", "sidecar_version"),
            selectedResource("agents", "agent_instance",
                    "id, instance_id, sidecar_id, status, agent_version, bootstrap_version, listen_host, listen_port, capabilities_json, last_heartbeat_at, created_at, updated_at",
                    "updated_at desc, id", "id", "listen_host", "status", "agent_version"),
            resource("agent-commands", "agent_command", "updated_at desc, id", "id", "agent_id", "command_type", "status"),
            resource("rules", "rule", "updated_at desc, id", "id", "name", "application_id", "environment_id", "status"),
            resource("rule-versions", "rule_version", "created_at desc, id", "id", "rule_id", "status", "risk_level"),
            resource("operation-plans", "operation_plan", "updated_at desc, id", "id", "resource_type", "resource_id", "status"),
            resource("rollout-batches", "rollout_batch", "updated_at desc, id", "id", "operation_plan_id", "status"),
            selectedResource("rollout-executions", "rollout_instance_execution",
                    """
                    id, rollout_batch_id, instance_id, status, expected_agent_version,
                    expected_rule_version, command_id, error_message, started_at, finished_at, updated_at,
                    (select rb.operation_plan_id from rollout_batch rb
                      where rb.id = rollout_instance_execution.rollout_batch_id) as operation_plan_id
                    """,
                    "updated_at desc, id", "id", "rollout_batch_id", "instance_id", "status"),
            resource("rollback-executions", "rollback_execution", "created_at desc, id",
                    "id", "operation_plan_id", "rollback_type", "status", "reason"),
            resource("recording-rules", "recording_rule", "updated_at desc, id", "id", "name", "status"),
            resource("recording-rule-versions", "recording_rule_version", "created_at desc, id", "id", "recording_rule_id", "status", "protocol"),
            resource("recording-sessions", "recording_session", "updated_at desc, id", "id", "application_id", "environment_id", "status"),
            resource("recording-batches", "recording_batch", "created_at desc, id", "id", "recording_session_id", "status", "object_uri"),
            resource("recording-events", "recording_event_index", "event_time desc, id", "id", "recording_session_id", "trace_id", "span_id", "protocol"),
            resource("datasets", "dataset_version", "created_at desc, id", "id", "dataset_id", "source_session_id", "retention_policy"),
            selectedResource("datasources", "datasource_registration",
                    "id, application_id, environment_id, datasource_type, name, status, created_by, created_at, updated_at",
                    "updated_at desc, id", "id", "name", "datasource_type", "status"),
            resource("extraction-templates", "extraction_template", "updated_at desc, id", "id", "name", "datasource_id", "status"),
            resource("extraction-tasks", "extraction_task", "updated_at desc, id", "id", "template_id", "dataset_id", "status"),
            resource("extraction-executions", "extraction_execution", "started_at desc, id", "id", "extraction_task_id", "status"),
            resource("extraction-results", "extraction_result", "created_at desc, id", "id", "extraction_task_id", "dataset_version_id", "result_type"),
            resource("replay-plans", "replay_plan", "updated_at desc, id", "id", "dataset_id", "target_environment", "target_application", "status"),
            resource("replay-executions", "replay_execution", "updated_at desc, id", "id", "replay_plan_id", "status"),
            resource("replay-batches", "replay_batch", "started_at desc, id", "id", "replay_execution_id", "status"),
            resource("replay-invocation-results", "replay_invocation_result", "created_at desc, id", "id", "replay_batch_id", "status"),
            resource("comparison-results", "comparison_result", "created_at desc, id", "id", "replay_invocation_result_id", "status"),
            resource("approvals", "approval_request", "updated_at desc, id", "id", "subject_type", "subject_id", "requester", "status"),
            resource("audits", "audit_record", "sequence desc", "id", "actor", "action", "resource_type", "resource_id"),
            resource("outbox", "outbox_event", "created_at desc, id", "id", "aggregate_type", "aggregate_id", "event_type", "status"),
            resource("worker-artifacts", "worker_artifact", "created_at desc, id", "id", "worker_type", "owner_type", "owner_id", "artifact_type"),
            selectedResource("tokens", "platform_access_token",
                    "id, subject_type, subject_id, display_name, status, created_by, created_at, expires_at, last_used_at, revoked_at",
                    "created_at desc, id", "id", "subject_type", "subject_id", "display_name", "status")
    );

    private final JdbcTemplate jdbcTemplate;

    public PlatformQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> page(String resource, int requestedPage, int requestedSize, String query) {
        ResourceDefinition definition = definition(resource);
        int page = Math.max(0, requestedPage);
        int size = Math.min(200, Math.max(1, requestedSize));
        String search = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
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
        ResourceDefinition definition = definition(resource);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select " + definition.selectColumns() + " from " + definition.table() + " where id = ?", id);
        if (rows.isEmpty()) {
            throw PlatformException.notFound(resource, id);
        }
        Map<String, Object> result = new LinkedHashMap<>(normalizeRow(rows.get(0)));
        result.put("allowed_actions", allowedActions(resource, String.valueOf(result.getOrDefault("status", ""))));
        return result;
    }

    public Map<String, Object> ruleDetail(String id) {
        Map<String, Object> rule = detail("rules", id);
        List<Map<String, Object>> versions = normalize(jdbcTemplate.queryForList(
                "select * from rule_version where rule_id = ? order by version desc", id));
        List<Map<String, Object>> targets = normalize(jdbcTemplate.queryForList("""
                select rt.*
                  from rule_target rt
                  join rule_version rv on rv.id = rt.rule_version_id
                 where rv.rule_id = ?
                 order by rv.version desc, rt.created_at, rt.id
                """, id));
        List<Map<String, Object>> capabilities = normalize(jdbcTemplate.queryForList("""
                select rc.*
                  from rule_capability rc
                  join rule_version rv on rv.id = rc.rule_version_id
                 where rv.rule_id = ?
                 order by rv.version desc, rc.capability
                """, id));
        return Map.of("rule", rule, "versions", versions, "targets", targets, "capabilities", capabilities);
    }

    public Map<String, Object> dashboard() {
        List<Map<String, Object>> recentAudits = normalize(jdbcTemplate.queryForList(
                "select * from audit_record order by sequence desc limit 8"));
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("agentsTotal", count("agent_instance"));
        counts.put("agentsOnline", countWhere("agent_instance", "status in ('ACTIVE', 'ONLINE')"));
        counts.put("rulesTotal", count("rule"));
        counts.put("rulesActive", countWhere("rule", "status in ('ACTIVE', 'PUBLISHED')"));
        counts.put("rolloutsRunning", countWhere("operation_plan", "status in ('SCHEDULED', 'RUNNING')"));
        counts.put("approvalsPending", countWhere("approval_request", "status = 'WAITING_APPROVAL'"));
        counts.put("recordingsRunning", countWhere("recording_session", "status = 'RECORDING'"));
        counts.put("workerArtifacts", count("worker_artifact"));
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
                 where (lower(rt.class_name) like ? or lower(rt.method_name) like ?)
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
                case "DRAFT" -> List.of("WAITING_APPROVAL", "CANCELLED");
                case "WAITING_APPROVAL" -> List.of("APPROVED", "CANCELLED", "EXPIRED");
                case "APPROVED" -> List.of("SCHEDULED", "RUNNING", "CANCELLED");
                case "SCHEDULED" -> List.of("RUNNING", "CANCELLED", "EXPIRED");
                case "RUNNING" -> List.of("OBSERVING", "SUCCEEDED", "PARTIALLY_SUCCEEDED",
                        "FAILED", "ROLLING_BACK", "CANCELLED");
                case "OBSERVING" -> List.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "ROLLING_BACK");
                case "PARTIALLY_SUCCEEDED", "FAILED" -> List.of("ROLLING_BACK", "CANCELLED");
                case "ROLLING_BACK" -> List.of("ROLLED_BACK", "FAILED");
                case "SUCCEEDED" -> List.of("UNLOAD");
                default -> List.of();
            };
            case "rollout-executions" -> "SUCCEEDED".equals(status)
                    ? List.of("UNLOAD_PLAN")
                    : List.of();
            case "recording-sessions" -> switch (status) {
                case "DRAFT" -> List.of("WAITING_APPROVAL", "CANCELLED");
                case "WAITING_APPROVAL" -> List.of("APPROVED", "CANCELLED", "EXPIRED");
                case "APPROVED" -> List.of("SCHEDULED", "RECORDING", "CANCELLED", "EXPIRED");
                case "SCHEDULED" -> List.of("RECORDING", "CANCELLED", "EXPIRED");
                case "RECORDING" -> List.of("PAUSED", "STOPPING", "COMPLETED", "FAILED", "EXPIRED");
                case "PAUSED" -> List.of("RECORDING", "STOPPING", "COMPLETED", "FAILED", "EXPIRED", "CANCELLED");
                case "STOPPING" -> List.of("COMPLETED", "FAILED");
                default -> List.of();
            };
            case "extraction-tasks" -> switch (status) {
                case "DRAFT" -> List.of("QUEUED", "CANCELLED");
                case "QUEUED" -> List.of("RUNNING", "CANCELLED");
                case "RUNNING" -> List.of("SUCCEEDED", "FAILED", "CANCELLED");
                case "FAILED" -> List.of("QUEUED", "CANCELLED");
                default -> List.of();
            };
            case "replay-plans" -> switch (status) {
                case "DRAFT" -> List.of("WAITING_APPROVAL", "CANCELLED");
                case "WAITING_APPROVAL" -> List.of("APPROVED", "CANCELLED", "EXPIRED");
                case "APPROVED" -> List.of("SCHEDULED", "RUNNING", "CANCELLED", "EXPIRED");
                case "SCHEDULED" -> List.of("RUNNING", "CANCELLED", "EXPIRED");
                case "RUNNING" -> List.of("OBSERVING", "SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "ROLLING_BACK");
                case "OBSERVING" -> List.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "ROLLING_BACK");
                case "PARTIALLY_SUCCEEDED", "FAILED" -> List.of("ROLLING_BACK");
                case "ROLLING_BACK" -> List.of("ROLLED_BACK", "FAILED");
                default -> List.of();
            };
            case "replay-executions" -> switch (status) {
                case "QUEUED" -> List.of("RUNNING", "CANCELLED");
                case "RUNNING" -> List.of("PAUSED", "SUCCEEDED", "FAILED", "CANCELLED");
                case "PAUSED" -> List.of("RUNNING", "CANCELLED");
                case "FAILED" -> List.of("QUEUED", "CANCELLED");
                default -> List.of();
            };
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
