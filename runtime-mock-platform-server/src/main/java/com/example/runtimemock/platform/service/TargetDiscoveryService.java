package com.example.runtimemock.platform.service;

import com.example.runtimemock.platform.command.AgentCommandService;
import com.example.runtimemock.platform.persistence.mapper.TargetDiscoveryMapper;
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
            String key = target.get("className") + "#" + target.get("methodName") + target.get("descriptor");
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
