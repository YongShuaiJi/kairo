package com.example.kairo.platform.service;

import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.command.TargetResolutionExchange;
import com.example.kairo.platform.command.TargetResolutionFailure;
import com.example.kairo.platform.command.TargetResolutionTimeoutException;
import com.example.kairo.platform.persistence.mapper.TargetDiscoveryMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Save-time enhancement-target resolution and drift validation (V1.3 §3.5).
 *
 * <p>Before a rule with a constructor or call-site location is persisted, the platform asks a live
 * agent in the rule's scope to resolve the {@link com.example.kairo.api.EnhancementTarget} against
 * the current bytecode. The agent returns a {@link com.example.kairo.api.TargetMatchResult}; this
 * service refuses to let the save proceed on {@code DRIFTED} (the call-site fingerprint changed),
 * {@code NOT_FOUND} (the member vanished) or {@code REJECTED} (native / abstract / unmodifiable /
 * unsupported opcode), so a recompiled or stale target is never silently woven. Method-location
 * rules - the three legacy phases and {@code METHOD_FINALLY} - keep the V1.2 save path and are not
 * resolved here, preserving "legacy rules run unchanged".
 *
 * <p>The same {@link #resolve} path backs the preview API so the Web can show match count and risk
 * before the author commits the save.
 */
@Service
public class EnhancementTargetResolutionService {

    private final AgentCommandService commands;
    private final TargetResolutionExchange exchange;
    private final TargetDiscoveryMapper targetDiscoveryMapper;

    @Value("${kairo.platform.target-resolution.timeout-ms:10000}")
    private long timeoutMillis;

    public EnhancementTargetResolutionService(AgentCommandService commands,
                                              TargetResolutionExchange exchange,
                                              TargetDiscoveryMapper targetDiscoveryMapper) {
        this.commands = commands;
        this.exchange = exchange;
        this.targetDiscoveryMapper = targetDiscoveryMapper;
    }

    /**
     * Resolve a target against a live agent in scope. Returns the agent's structured result
     * (status, matchedCount, reason, risk, occurrenceCount/occurrenceIndex, resolvedIdentity).
     * Throws {@code TARGET_RESOLUTION_UNAVAILABLE} when no agent is online, and maps agent failures
     * and timeouts to platform conflicts.
     */
    public Map<String, Object> resolve(RequestContext context, String applicationId, String environmentId,
                                       Map<String, Object> target) {
        String agentId = pickAgent(applicationId, environmentId);
        if (agentId == null) {
            throw PlatformException.conflict("TARGET_RESOLUTION_UNAVAILABLE",
                    "没有在线 Agent 可用于校验增强位置目标，请确保目标实例已注册并在线后重试",
                    Map.of("applicationId", applicationId, "environmentId", environmentId));
        }
        Map<String, Object> payload = resolutionPayload(target);
        Map<String, Object> command = commands.createTargetResolutionCommand(context, agentId, payload);
        String commandId = String.valueOf(command.get("id"));
        try {
            return exchange.await(commandId, Duration.ofMillis(Math.max(1000L, Math.min(timeoutMillis, 60_000L))));
        } catch (TargetResolutionTimeoutException e) {
            throw PlatformException.conflict("TARGET_RESOLUTION_TIMEOUT",
                    "目标解析超时，Agent 未在限定时间内响应",
                    Map.of("agentId", agentId, "commandId", commandId));
        } catch (TargetResolutionFailure e) {
            throw PlatformException.conflict("TARGET_RESOLUTION_FAILED",
                    "目标解析失败：" + e.getMessage(),
                    mergeDetails(Map.of("agentId", agentId, "commandId", commandId), e.result()));
        } finally {
            exchange.remove(commandId);
        }
    }

    /**
     * Resolve and validate a target before persisting a rule version. Returns the MATCHED result
     * (carrying the fresh call-site fingerprint the caller should stamp back into the selector).
     * Throws on every non-MATCHED outcome so the rule save is aborted upstream.
     */
    public Map<String, Object> resolveAndValidate(RequestContext context, String applicationId,
                                                  String environmentId, Map<String, Object> target) {
        Map<String, Object> result = resolve(context, applicationId, environmentId, target);
        String status = String.valueOf(result.get("status"));
        return switch (status) {
            case "MATCHED" -> result;
            case "AMBIGUOUS" -> throw PlatformException.conflict("AMBIGUOUS_TARGET",
                    "增强目标同名候选多于一个，请指定具体 classLoaderId 或显式 all-match",
                    resolutionDetails(result));
            case "DRIFTED" -> throw PlatformException.conflict("TARGET_DRIFTED",
                    "调用点位置已漂移，指纹与当前字节码不一致，请重新选择调用点",
                    resolutionDetails(result));
            case "NOT_FOUND" -> throw PlatformException.conflict("TARGET_NOT_FOUND",
                    "增强目标在当前字节码中不存在，请确认类、方法或调用点仍然存在",
                    resolutionDetails(result));
            case "REJECTED" -> throw PlatformException.badRequest("TARGET_REJECTED",
                    "增强目标不可增强：" + result.getOrDefault("reason", "目标被拒绝"));
            default -> throw PlatformException.conflict("TARGET_RESOLUTION_UNEXPECTED",
                    "目标解析返回未知状态：" + status, resolutionDetails(result));
        };
    }

    private String pickAgent(String applicationId, String environmentId) {
        List<Map<String, Object>> agents = targetDiscoveryMapper.activeAgents(applicationId, environmentId);
        if (agents.isEmpty()) {
            return null;
        }
        return String.valueOf(agents.get(0).get("agent_id"));
    }

    /** Build the {@code RESOLVE_TARGET} payload from a rule-target request map. */
    private Map<String, Object> resolutionPayload(Map<String, Object> target) {
        Map<String, Object> matcher = readMatcher(target);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", "RESOLVE_TARGET");
        payload.put("classId", classIdOf(target, matcher));
        payload.put("className", text(target, "className", text(target, "class_name", "")));
        payload.put("methodName", text(target, "methodName", text(target, "method_name", "")));
        payload.put("methodDescriptor", text(matcher, "descriptor", ""));
        String location = text(target, "location", null);
        if (location == null || location.isBlank()) {
            throw PlatformException.badRequest("FIELD_REQUIRED",
                    "缺少必填字段：location（构造器与调用点目标必须显式指定增强位置）");
        }
        payload.put("location", location.toUpperCase(Locale.ROOT));
        Object callSiteSelector = target.get("callSiteSelector");
        if (callSiteSelector != null) {
            payload.put("callSiteSelector", callSiteSelector);
        }
        return payload;
    }

    private Map<String, Object> readMatcher(Map<String, Object> target) {
        Object value = target.get("matcher");
        if (value instanceof Map<?, ?> map) {
            return PlatformJson.stringKeyMap(map);
        }
        if (value instanceof String text && !text.isBlank()) {
            return PlatformJson.readMap(text);
        }
        return Map.of();
    }

    private String classIdOf(Map<String, Object> target, Map<String, Object> matcher) {
        String fromMatcher = text(matcher, "classId", null);
        if (fromMatcher != null && !fromMatcher.isBlank()) {
            return fromMatcher;
        }
        return text(target, "className", text(target, "class_name", ""));
    }

    private Map<String, Object> resolutionDetails(Map<String, Object> result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", result.get("status"));
        if (result.get("matchedCount") != null) {
            details.put("matchedCount", result.get("matchedCount"));
        }
        if (result.get("reason") != null) {
            details.put("reason", result.get("reason"));
        }
        if (result.get("occurrenceCount") != null) {
            details.put("occurrenceCount", result.get("occurrenceCount"));
        }
        if (result.get("occurrenceIndex") != null) {
            details.put("occurrenceIndex", result.get("occurrenceIndex"));
        }
        return details;
    }

    private Map<String, Object> mergeDetails(Map<String, Object> base, Map<String, Object> extra) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(resolutionDetails(extra));
        return merged;
    }

    private String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }
}
