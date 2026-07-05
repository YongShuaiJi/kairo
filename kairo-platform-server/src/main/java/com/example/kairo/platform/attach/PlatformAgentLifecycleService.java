package com.example.kairo.platform.attach;

import com.example.kairo.platform.command.AgentCommandService;
import com.example.kairo.platform.persistence.mapper.AgentLifecycleMapper;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.PlatformCoreService;
import com.example.kairo.platform.service.RbacService;
import com.example.kairo.platform.service.RequestContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.InvocationTargetException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PlatformAgentLifecycleService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AgentLifecycleMapper lifecycleMapper;
    private final RbacService rbacService;
    private final AgentCommandService commandService;
    private final AttachExecutorCommandService attachExecutorCommandService;
    private final PlatformCoreService eventWriter;
    private final PlatformAttachProperties properties;
    private final Clock clock;

    @Autowired
    public PlatformAgentLifecycleService(AgentLifecycleMapper lifecycleMapper,
                                         RbacService rbacService,
                                         AgentCommandService commandService,
                                         AttachExecutorCommandService attachExecutorCommandService,
                                         PlatformCoreService eventWriter,
                                         PlatformAttachProperties properties) {
        this(lifecycleMapper, rbacService, commandService, attachExecutorCommandService,
                eventWriter, properties, Clock.systemUTC());
    }

    PlatformAgentLifecycleService(AgentLifecycleMapper lifecycleMapper,
                                  RbacService rbacService,
                                  AgentCommandService commandService,
                                  AttachExecutorCommandService attachExecutorCommandService,
                                  PlatformCoreService eventWriter,
                                  PlatformAttachProperties properties,
                                  Clock clock) {
        this.lifecycleMapper = lifecycleMapper;
        this.rbacService = rbacService;
        this.commandService = commandService;
        this.attachExecutorCommandService = attachExecutorCommandService;
        this.eventWriter = eventWriter;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> attach(RequestContext context, String instanceId, Map<String, Object> request) {
        return attach(context, instanceId, request, false);
    }

    @Transactional
    public Map<String, Object> reload(RequestContext context, String instanceId, Map<String, Object> request) {
        return attach(context, instanceId, request, true);
    }

    @Transactional
    public Map<String, Object> deactivate(RequestContext context, String instanceId, Map<String, Object> request) {
        rbacService.require(context, "AGENT_MANAGE");
        Map<String, Object> instance = instance(instanceId);
        Map<String, Object> agent = latestAgent(instanceId);
        if (agent == null) {
            throw PlatformException.conflict("AGENT_NOT_REGISTERED",
                    "该实例还没有已注册 Agent，无法停用", Map.of("instanceId", instanceId));
        }
        String agentId = String.valueOf(agent.get("id"));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandType", "STOP_AGENT");
        payload.put("reason", optionalString(request, "reason", "通过平台停用 Agent 并恢复字节码"));
        Map<String, Object> command = commandService.enqueue(context, agentId, "STOP_AGENT", payload,
                "agent-lifecycle:deactivate:" + agentId + ":" + UUID.randomUUID(),
                Math.max(1, properties.getCommandMaxAttempts()), clock.instant());
        lifecycleMapper.markAgentStopping(agentId, timestamp(clock.instant()));
        Map<String, Object> result = Map.of(
                "operation", "DEACTIVATE_AGENT",
                "status", "COMMAND_ENQUEUED",
                "instanceId", instanceId,
                "agentId", agentId,
                "commandId", command.get("id")
        );
        eventWriter.recordEvent(context, "agent.lifecycle.deactivate", "instance", instanceId, 1,
                instance, result, "SUCCESS", "停用 Agent",
                Map.of("agentId", agentId, "commandId", command.get("id")));
        return result;
    }

    private Map<String, Object> attach(RequestContext context, String instanceId,
                                       Map<String, Object> request, boolean reload) {
        rbacService.require(context, "AGENT_MANAGE");
        Map<String, Object> instance = instance(instanceId);
        Map<String, Object> sidecar = latestSidecar(instanceId);
        String processId = optionalString(request, "processId", String.valueOf(instance.get("process_id")));
        if (sidecar != null && sidecar.get("process_id") != null) {
            processId = optionalString(request, "processId", String.valueOf(sidecar.get("process_id")));
        }
        if (sidecar == null || sidecar.get("executor_id") == null
                || String.valueOf(sidecar.get("executor_id")).isBlank()) {
            throw PlatformException.conflict("ATTACH_EXECUTOR_REQUIRED",
                    "该实例没有可用 Attach 执行器；动态加载只支持执行器长轮询模式",
                    Map.of("instanceId", instanceId));
        }
        String defaultAgentJar = sidecar != null && sidecar.get("agent_jar") != null
                ? String.valueOf(sidecar.get("agent_jar"))
                : properties.getAgentJar();
        String agentJar = optionalString(request, "agentJar", defaultAgentJar);
        String coreJar = optionalString(request, "coreJar", properties.getCoreJar());
        String bootstrapJar = optionalString(request, "bootstrapJar", properties.getBootstrapJar());
        boolean agentReload = reload || optionalBoolean(request, "reload", true);
        String args = agentArgs(instance, request, agentJar, coreJar, bootstrapJar, agentReload);
        Map<String, Object> before = new LinkedHashMap<>(instance);
        String operation = reload ? "RELOAD_AGENT" : "ATTACH_AGENT";
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation);
        payload.put("instanceId", instanceId);
        payload.put("processId", processId);
        payload.put("agentJar", agentJar);
        payload.put("agentArgs", args);
        payload.put("reload", agentReload);
        String commandIdempotencyKey = optionalString(request, "idempotencyKey",
                "agent-lifecycle:" + operation + ":" + instanceId + ":" + UUID.randomUUID());
        Map<String, Object> command = attachExecutorCommandService.enqueue(context,
                String.valueOf(sidecar.get("executor_id")), instanceId, operation,
                processId, agentJar, args, payload, commandIdempotencyKey,
                (int) Math.max(1, properties.getCommandMaxAttempts()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation", operation);
        result.put("status", "COMMAND_ENQUEUED");
        result.put("instanceId", instanceId);
        result.put("processId", processId);
        result.put("commandId", command.get("id"));
        result.put("agentJar", agentJar);
        result.put("executor", "attach-executor");
        result.put("executorId", sidecar.get("executor_id"));
        result.put("sidecarId", sidecar.get("sidecar_id"));
        result.put("coreJar", coreJar);
        result.put("bootstrapJar", bootstrapJar);
        eventWriter.recordEvent(context, reload ? "agent.lifecycle.reload" : "agent.lifecycle.attach",
                "instance", instanceId, 1, before, result, "SUCCESS",
                reload ? "创建重新加载 Agent 命令" : "创建 attach 加载 Agent 命令",
                Map.of("processId", processId, "reload", agentReload, "commandId", command.get("id")));
        return result;
    }

    private String agentArgs(Map<String, Object> instance, Map<String, Object> request,
                             String agentJar, String coreJar, String bootstrapJar, boolean reload) {
        StringBuilder builder = new StringBuilder("attach=true");
        appendValue(builder, "reload", String.valueOf(reload));
        appendValue(builder, "host", optionalString(request, "host", properties.getAgentHost()));
        appendValue(builder, "port", String.valueOf(optionalLong(request, "port", properties.getAgentPort())));
        appendValue(builder, "token", generateToken());
        appendValue(builder, "coreJar", coreJar);
        appendValue(builder, "bootstrapJar", bootstrapJar);
        appendValue(builder, "platformUrl", optionalString(request, "platformUrl", properties.getPlatformUrl()));
        appendValue(builder, "platformToken", optionalString(request, "platformToken", properties.getPlatformToken()));
        appendValue(builder, "platformInstanceId", String.valueOf(instance.get("id")));
        appendValue(builder, "platformApplicationId", String.valueOf(instance.get("application_id")));
        Object environmentId = instance.get("environment_id");
        if (environmentId != null && !String.valueOf(environmentId).isBlank()) {
            appendValue(builder, "platformEnvironmentId", String.valueOf(environmentId));
        }
        appendValue(builder, "platformNickname", optionalString(request, "nickname",
                String.valueOf(instance.getOrDefault("nickname", ""))));
        appendValue(builder, "platformPollIntervalMillis",
                String.valueOf(optionalLong(request, "pollIntervalMillis", 1000L)));
        appendValue(builder, "agentJar", agentJar);
        return builder.toString();
    }

    private Map<String, Object> instance(String instanceId) {
        Map<String, Object> instance = lifecycleMapper.findInstance(instanceId);
        if (instance == null) {
            throw PlatformException.notFound("instance", instanceId);
        }
        return instance;
    }

    private Map<String, Object> latestAgent(String instanceId) {
        return lifecycleMapper.latestAgent(instanceId);
    }

    private Map<String, Object> latestSidecar(String instanceId) {
        Map<String, Object> executorSidecar = lifecycleMapper.latestExecutorSidecar(instanceId);
        if (executorSidecar != null) {
            return executorSidecar;
        }
        return lifecycleMapper.latestStandaloneSidecar(instanceId);
    }

    private String attachFailureHint(Throwable failure) {
        String message = failure.getMessage() == null ? "" : failure.getMessage();
        String type = failure.getClass().getName();
        if (type.contains("AttachNotSupportedException")) {
            return "目标 JVM 不支持 attach，可能是 PID 不可见、不是 Java 进程，或容器 PID 命名空间隔离";
        }
        if (message.contains("Permission denied")) {
            return "权限不足，平台进程需要能访问目标 JVM 进程";
        }
        if (message.contains("DisableAttachMechanism")) {
            return "目标 JVM 启用了 -XX:+DisableAttachMechanism";
        }
        if (message.contains("No such process")) {
            return "找不到目标进程，请确认 PID 对平台进程可见";
        }
        return message.isBlank() ? type : message;
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof InvocationTargetException invocationTargetException
                && invocationTargetException.getTargetException() != null) {
            return unwrap(invocationTargetException.getTargetException());
        }
        if (throwable.getCause() != null && throwable instanceof ReflectiveOperationException) {
            return unwrap(throwable.getCause());
        }
        return throwable;
    }

    private String optionalString(Map<String, Object> values, String key, String defaultValue) {
        Object value = values.get(key);
        return value == null || String.valueOf(value).isBlank() ? defaultValue : String.valueOf(value).trim();
    }

    private long optionalLong(Map<String, Object> values, String key, long defaultValue) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private boolean optionalBoolean(Map<String, Object> values, String key, boolean defaultValue) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private void appendValue(StringBuilder builder, String key, String value) {
        if (value != null && !value.isBlank()) {
            builder.append(',').append(key).append('=').append(value);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
