package com.example.runtimemock.platform.service;

import com.example.runtimemock.platform.persistence.mapper.PlatformQueryMapper;
import com.example.runtimemock.platform.persistence.mapper.RuleLedgerQueryMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@Service
public final class PlatformQueryService {

    private static final Set<String> RESOURCES = Set.of(
            "applications", "rollout-environments", "rollout-applications", "environments",
            "instances", "sidecars", "attach-executors", "attach-targets",
            "attach-executor-commands", "agents", "agent-commands", "rules",
            "rule-versions", "operation-plans", "rollout-executions", "rollout-targets",
            "rollback-executions", "tokens", "users");

    private final PlatformQueryMapper queryMapper;
    private final RuleLedgerQueryMapper ruleLedgerQueryMapper;

    public PlatformQueryService(PlatformQueryMapper queryMapper, RuleLedgerQueryMapper ruleLedgerQueryMapper) {
        this.queryMapper = queryMapper;
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

        requireResource(resource);
        String like = "%" + search + "%";
        List<Map<String, Object>> items = normalize(queryMapper.page(resource, size, page * size, search, like));
        long total = queryMapper.count(resource, search, like);
        return Map.of(
                "items", items,
                "page", page,
                "size", size,
                "total", total
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

        requireResource(resource);
        Map<String, Object> row = queryMapper.detail(resource, id);
        if (row == null) {
            throw PlatformException.notFound(resource, id);
        }
        Map<String, Object> result = new LinkedHashMap<>(normalizeRow(row));
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
        List<Map<String, Object>> agents = normalize(queryMapper.latestAgentForInstance(instanceId));
        result.put("agent", agents.isEmpty() ? null : agents.get(0));
        List<Map<String, Object>> executors = normalize(queryMapper.latestAttachExecutorForInstance(instanceId));
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
        List<Map<String, Object>> recentAudits = normalize(queryMapper.recentAudits());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("agentsTotal", queryMapper.countAgentsTotal());
        counts.put("agentsOnline", queryMapper.countAgentsOnline());
        counts.put("instancesTotal", queryMapper.countInstancesTotal());
        counts.put("injectableInstancesOnline", queryMapper.countInjectableInstancesOnline());
        counts.put("rulesTotal", queryMapper.countRulesTotal());
        counts.put("rulesActive", queryMapper.countRulesActive());
        counts.put("rolloutsRunning", queryMapper.countRolloutsRunning());
        Map<String, Long> trendCounts = new TreeMap<>();
        normalize(queryMapper.allAudits()).forEach(row -> {
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
        return normalize(queryMapper.searchTargets("%" + search + "%", application, environment));
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

    private void requireResource(String resource) {
        if (!RESOURCES.contains(resource)) {
            throw PlatformException.notFound("query-resource", resource);
        }
    }

    private List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        return rows.stream().map(this::normalizeRow).toList();
    }

    private Map<String, Object> normalizeRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach((key, value) -> result.put(key.toLowerCase(Locale.ROOT), value));
        return result;
    }

}
