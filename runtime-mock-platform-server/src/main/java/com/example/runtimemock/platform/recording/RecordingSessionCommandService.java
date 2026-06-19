package com.example.runtimemock.platform.recording;

import com.example.runtimemock.platform.command.AgentCommandService;
import com.example.runtimemock.platform.service.PlatformJdbcService;
import com.example.runtimemock.platform.service.PlatformJson;
import com.example.runtimemock.platform.service.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RecordingSessionCommandService {

    private final PlatformJdbcService platformService;
    private final AgentCommandService commandService;
    private final JdbcTemplate jdbcTemplate;

    public RecordingSessionCommandService(PlatformJdbcService platformService,
                                          AgentCommandService commandService,
                                          JdbcTemplate jdbcTemplate) {
        this.platformService = platformService;
        this.commandService = commandService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Map<String, Object> transition(String id, RequestContext context, Map<String, Object> request) {
        Map<String, Object> transitioned = platformService.transitionRecordingSession(id, context, request);
        String targetStatus = String.valueOf(request.get("targetStatus"));
        String commandType = "RECORDING".equals(targetStatus)
                ? "START_RECORDING"
                : isTerminal(targetStatus) ? "STOP_RECORDING" : null;
        if (commandType == null) {
            return transitioned;
        }

        Map<String, Object> target = PlatformJson.readMap(
                String.valueOf(transitioned.getOrDefault("target_json", "{}")));
        if ("START_RECORDING".equals(commandType) && !isConcreteJavaTarget(target)) {
            return transitioned;
        }

        List<String> agentIds = targetAgentIds(transitioned, target);
        List<String> commandIds = new ArrayList<>();
        for (String agentId : agentIds) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("commandType", commandType);
            payload.put("sessionId", id);
            if ("START_RECORDING".equals(commandType)) {
                payload.put("target", target);
            }
            Map<String, Object> command = commandService.enqueue(
                    context,
                    agentId,
                    commandType,
                    payload,
                    "recording:" + id + ":" + commandType + ":" + agentId,
                    5,
                    Instant.now()
            );
            commandIds.add(String.valueOf(command.get("id")));
        }
        Map<String, Object> response = new LinkedHashMap<>(transitioned);
        response.put("agent_command_ids", commandIds);
        return response;
    }

    private List<String> targetAgentIds(Map<String, Object> session, Map<String, Object> target) {
        Object configured = target.get("agentIds");
        if (configured instanceof Collection<?> collection && !collection.isEmpty()) {
            return collection.stream().map(String::valueOf).distinct().toList();
        }
        return jdbcTemplate.queryForList("""
                select a.id
                  from agent_instance a
                  join instance i on i.id = a.instance_id
                 where a.status = 'ACTIVE'
                   and i.application_id = ?
                   and i.environment_id = ?
                 order by a.id
                """, String.class, session.get("application_id"), session.get("environment_id"));
    }

    private boolean isConcreteJavaTarget(Map<String, Object> target) {
        Object matcherValue = target.get("matcher");
        Map<String, Object> matcher = matcherValue instanceof Map<?, ?> map
                ? PlatformJson.stringKeyMap(map)
                : Map.of();
        return hasText(target.get("classId")) || hasText(target.get("className"))
                ? hasText(target.get("methodName"))
                && (hasText(target.get("methodDescriptor")) || hasText(matcher.get("descriptor")))
                : false;
    }

    private boolean hasText(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    private boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "CANCELLED".equals(status) || "FAILED".equals(status);
    }
}
