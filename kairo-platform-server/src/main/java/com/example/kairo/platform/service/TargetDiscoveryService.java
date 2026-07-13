package com.example.kairo.platform.service;

import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.persistence.mapper.TargetDiscoveryMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public final class TargetDiscoveryService {

    private final TargetDiscoveryMapper targetDiscoveryMapper;
    private final AgentCommandService commandService;

    public TargetDiscoveryService(TargetDiscoveryMapper targetDiscoveryMapper, AgentCommandService commandService) {
        this.targetDiscoveryMapper = targetDiscoveryMapper;
        this.commandService = commandService;
    }

    public List<Map<String, Object>> search(RequestContext context, String query,
                                            String applicationId, String environmentId) {
        String application = required(applicationId, "applicationId");
        String environment = required(environmentId, "environmentId");
        String search = query == null ? "" : query.trim();
        List<Map<String, Object>> agents = normalize(targetDiscoveryMapper.activeAgents(application, environment));
        if (agents.isEmpty()) {
            return List.of();
        }
        long bucket = Instant.now().getEpochSecond() / 5;
        List<Map<String, Object>> commands = new ArrayList<>();
        for (Map<String, Object> agent : agents) {
            String agentId = String.valueOf(agent.get("agent_id"));
            Map<String, Object> command = commandService.enqueue(
                    context,
                    agentId,
                    "DISCOVER_TARGETS",
                    Map.of("commandType", "DISCOVER_TARGETS", "query", search, "limit", 200),
                    "discover:" + agentId + ":" + application + ":" + environment + ":"
                            + search.toLowerCase() + ":" + bucket,
                    2,
                    Instant.now());
            commands.add(command);
        }
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        Map<String, Map<String, Object>> aggregate = new LinkedHashMap<>();
        while (!commands.isEmpty() && System.nanoTime() < deadline) {
            commands.removeIf(command -> collectIfCompleted(command, agents, aggregate));
            if (!commands.isEmpty()) {
                sleep();
            }
        }
        return aggregate.values().stream().limit(200).toList();
    }

    /**
     * V1.3 §3.5: read-only enumeration of call-site candidates inside a caller method, for the
     * guided call-site selector. Dispatches {@code LIST_CALL_SITES} to one live agent in scope and
     * returns every matching {@code invoke*} instruction in visit order with its occurrence index
     * and freshly captured fingerprint, so the platform can present choices and persist a stable
     * identity. Returns an empty candidate list when no agent is online.
     */
    public Map<String, Object> listCallSites(RequestContext context, String applicationId,
                                             String environmentId, Map<String, Object> request) {
        String application = required(applicationId, "applicationId");
        String environment = required(environmentId, "environmentId");
        String classId = requiredText(request, "classId");
        String callerMethodName = requiredText(request, "callerMethodName");
        String callerMethodDescriptor = requiredText(request, "callerMethodDescriptor");
        List<Map<String, Object>> agents = normalize(targetDiscoveryMapper.activeAgents(application, environment));
        if (agents.isEmpty()) {
            return Map.of("candidates", List.of(), "count", 0, "agentAvailable", false);
        }
        String agentId = String.valueOf(agents.get(0).get("agent_id"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", "LIST_CALL_SITES");
        payload.put("classId", classId);
        payload.put("callerMethodName", callerMethodName);
        payload.put("callerMethodDescriptor", callerMethodDescriptor);
        copyIfPresent(request, payload, "calleeOwner");
        copyIfPresent(request, payload, "calleeName");
        copyIfPresent(request, payload, "calleeDescriptor");
        copyIfPresent(request, payload, "opcode");
        long bucket = Instant.now().getEpochSecond() / 5;
        Map<String, Object> command = commandService.enqueue(
                context,
                agentId,
                "LIST_CALL_SITES",
                payload,
                "call-sites:" + agentId + ":" + classId + ":" + callerMethodName
                        + callerMethodDescriptor + ":" + PlatformJson.sha256(payload) + ":" + bucket,
                2,
                Instant.now());
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> current = commandService.command(String.valueOf(command.get("id")));
            String status = String.valueOf(current.get("status"));
            if ("FAILED".equals(status)) {
                throw PlatformException.conflict("CALL_SITE_SCAN_FAILED",
                        "调用点扫描失败",
                        Map.of("agentId", agentId, "commandId", command.get("id")));
            }
            if ("ACKED".equals(status)) {
                Map<String, Object> result = PlatformJson.readMap(String.valueOf(current.get("result_json")));
                Map<String, Object> enriched = new LinkedHashMap<>(result);
                enriched.put("agentId", agentId);
                enriched.put("agentAvailable", true);
                return enriched;
            }
            sleep();
        }
        throw PlatformException.conflict("CALL_SITE_SCAN_TIMEOUT",
                "调用点扫描超时，Agent 未在限定时间内响应",
                Map.of("agentId", agentId, "commandId", command.get("id")));
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        Object value = source.get(key);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(key, value);
        }
    }

    /**
     * V1.5 §4.1/§5: the ClassLoader tree for the Web class selector. Dispatches
     * {@code LIST_LOADERS} to one live agent in scope and returns every tracked loader
     * (bootstrap first) plus a parent&rarr;children tree keyed by loader id, so an operator
     * can pick a {@code classLoaderId} and disambiguate same-name classes across loaders.
     * Returns an empty tree when no agent is online.
     */
    public Map<String, Object> listLoaders(RequestContext context, String applicationId,
                                           String environmentId) {
        String application = required(applicationId, "applicationId");
        String environment = required(environmentId, "environmentId");
        List<Map<String, Object>> agents = normalize(targetDiscoveryMapper.activeAgents(application, environment));
        if (agents.isEmpty()) {
            return Map.of("loaders", List.of(), "tree", Map.of(), "count", 0,
                    "agentAvailable", false, "bootstrapLoaderId", "bootstrap");
        }
        String agentId = String.valueOf(agents.get(0).get("agent_id"));
        long bucket = Instant.now().getEpochSecond() / 5;
        Map<String, Object> command = commandService.enqueue(
                context,
                agentId,
                "LIST_LOADERS",
                Map.of("commandType", "LIST_LOADERS"),
                "loaders:" + agentId + ":" + application + ":" + environment + ":" + bucket,
                2,
                Instant.now());
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            Map<String, Object> current = commandService.command(String.valueOf(command.get("id")));
            String status = String.valueOf(current.get("status"));
            if ("FAILED".equals(status)) {
                throw PlatformException.conflict("LIST_LOADERS_FAILED",
                        "ClassLoader 树查询失败",
                        Map.of("agentId", agentId, "commandId", command.get("id")));
            }
            if ("ACKED".equals(status)) {
                Map<String, Object> result = PlatformJson.readMap(String.valueOf(current.get("result_json")));
                Map<String, Object> enriched = new LinkedHashMap<>(result);
                enriched.put("agentId", agentId);
                enriched.put("agentAvailable", true);
                return enriched;
            }
            sleep();
        }
        throw PlatformException.conflict("LIST_LOADERS_TIMEOUT",
                "ClassLoader 树查询超时，Agent 未在限定时间内响应",
                Map.of("agentId", agentId, "commandId", command.get("id")));
    }

    private String requiredText(Map<String, Object> request, String key) {
        Object value = request.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "缺少必填字段：" + key);
        }
        return String.valueOf(value);
    }

    private boolean collectIfCompleted(Map<String, Object> command,
                                       List<Map<String, Object>> agents,
                                       Map<String, Map<String, Object>> aggregate) {
        Map<String, Object> current = commandService.command(String.valueOf(command.get("id")));
        String status = String.valueOf(current.get("status"));
        if ("FAILED".equals(status)) {
            return true;
        }
        if (!"ACKED".equals(status)) {
            return false;
        }
        Map<String, Object> result = PlatformJson.readMap(String.valueOf(current.get("result_json")));
        Object rawTargets = result.get("targets");
        if (!(rawTargets instanceof List<?> targets)) {
            return true;
        }
        String agentId = String.valueOf(current.get("agent_id"));
        String instanceId = agents.stream()
                .filter(item -> agentId.equals(String.valueOf(item.get("agent_id"))))
                .map(item -> String.valueOf(item.get("instance_id")))
                .findFirst()
                .orElse("");
        for (Object item : targets) {
            if (!(item instanceof Map<?, ?> raw)) {
                continue;
            }
            Map<String, Object> target = PlatformJson.stringKeyMap(raw);
            // V1.5 §4.1: key by classLoaderId as well as class+method+descriptor so the same
            // binary name loaded by two different ClassLoaders is presented as two distinct
            // targets (the loader tree lets the operator disambiguate), never collapsed into one.
            String loaderId = String.valueOf(target.getOrDefault("classLoaderId", ""));
            String key = target.get("className") + "#" + target.get("methodName")
                    + target.get("descriptor") + "@" + loaderId;
            Map<String, Object> entry = aggregate.computeIfAbsent(key, ignored -> {
                Map<String, Object> value = new LinkedHashMap<>(target);
                value.put("protocol", "JAVA_METHOD");
                value.put("agentIds", new ArrayList<String>());
                value.put("instanceIds", new ArrayList<String>());
                return value;
            });
            addUnique(entry, "agentIds", agentId);
            addUnique(entry, "instanceIds", instanceId);
            entry.put("agentCount", ((List<?>) entry.get("agentIds")).size());
            entry.put("instanceCount", ((List<?>) entry.get("instanceIds")).size());
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private void addUnique(Map<String, Object> entry, String key, String value) {
        List<String> values = (List<String>) entry.get(key);
        if (!value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("目标发现等待被中断", e);
        }
    }

    private String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED", "缺少必填字段：" + name);
        }
        return value.trim();
    }

    private List<Map<String, Object>> normalize(List<Map<String, Object>> rows) {
        return rows.stream().map(row -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach((key, value) -> normalized.put(key.toLowerCase(), value));
            return normalized;
        }).toList();
    }
}
