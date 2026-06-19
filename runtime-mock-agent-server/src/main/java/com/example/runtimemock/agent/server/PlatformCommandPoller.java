package com.example.runtimemock.agent.server;

import com.example.runtimemock.agent.core.AgentRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class PlatformCommandPoller implements AutoCloseable {

    private final AgentRuntime runtime;
    private final AgentLaunchConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ScheduledExecutorService executor;

    PlatformCommandPoller(AgentRuntime runtime, AgentLaunchConfig config) {
        this.runtime = runtime;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "runtime-mock-platform-command-poller");
            thread.setDaemon(true);
            return thread;
        });
    }

    void start() {
        executor.scheduleWithFixedDelay(this::pollSafely, 0L,
                config.platformPollIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        try {
            pollOnce();
        } catch (Exception e) {
            runtime.recordEvent("platform.command.poll_failed", "platform", null, null,
                    e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private void pollOnce() throws Exception {
        JsonNode command = post("/api/v1/agents/" + config.platformAgentId() + "/commands/next",
                Map.of("leaseSeconds", config.platformCommandLeaseSeconds()));
        if ("NO_COMMAND".equals(command.path("status").asText())) {
            return;
        }
        String commandId = command.path("id").asText();
        try {
            Map<String, Object> result = execute(command);
            post("/api/v1/agent-commands/" + commandId + "/ack",
                    Map.of("status", "ACKED", "result", result, "reason", "agent command applied"));
        } catch (Exception e) {
            post("/api/v1/agent-commands/" + commandId + "/ack",
                    Map.of("status", "FAILED",
                            "errorMessage", e.getClass().getName() + ": " + e.getMessage(),
                            "reason", "agent command failed"));
        }
    }

    private Map<String, Object> execute(JsonNode command) {
        JsonNode payload = command.path("payload");
        String commandType = payload.path("commandType").asText(command.path("command_type").asText());
        return switch (commandType) {
            case "APPLY_RULE" -> applyRule(payload.path("rule"));
            case "DISABLE_ALL" -> {
                runtime.disableAll(true);
                yield Map.of("disabled", true);
            }
            case "ENABLE_ALL" -> {
                runtime.disableAll(false);
                yield Map.of("disabled", false);
            }
            case "RESET_CLASS" -> {
                String classId = payload.path("classId").asText(payload.path("className").asText());
                var result = runtime.resetClass(classId, "platform");
                yield Map.of("classId", classId, "remainingRules", result.remainingRules().size(),
                        "removedRuleIds", result.removedRuleIds(), "failedRules", result.failedRules(),
                        "degraded", result.degraded());
            }
            case "RESET_ALL" -> {
                runtime.resetAll("platform");
                yield Map.of("resetAll", true);
            }
            case "START_RECORDING" -> startRecording(payload);
            case "STOP_RECORDING" -> stopRecording(payload);
            case "REFRESH_RUNTIME_STATE" -> Map.of("refreshed", true);
            default -> throw new IllegalArgumentException("Unsupported platform command: " + commandType);
        };
    }

    private Map<String, Object> applyRule(JsonNode ruleNode) {
        AgentHttpServer.RuleRequest request = AgentHttpServer.RuleRequest.from(ruleNode);
        runtime.publish(request.classId(), request.toRule(runtime.methods(request.classId())), "platform");
        return Map.of("ruleId", request.id(), "version", request.version(), "classId", request.classId());
    }

    private Map<String, Object> startRecording(JsonNode payload) {
        JsonNode target = payload.path("target");
        String sessionId = requiredText(payload, "sessionId");
        String classTarget = text(target, "classId", text(target, "className", null));
        String methodName = text(target, "methodName", null);
        String methodDescriptor = text(target, "methodDescriptor",
                target.path("matcher").path("descriptor").asText(null));
        var registration = runtime.startRecording(
                sessionId, classTarget, methodName, methodDescriptor, "platform");
        return Map.of(
                "sessionId", registration.sessionId(),
                "classId", registration.classId(),
                "className", registration.className(),
                "methodName", registration.methodName(),
                "methodDescriptor", registration.methodDescriptor()
        );
    }

    private Map<String, Object> stopRecording(JsonNode payload) {
        String sessionId = requiredText(payload, "sessionId");
        var registration = runtime.stopRecording(sessionId, "platform");
        return Map.of("sessionId", sessionId, "stopped", registration != null);
    }

    private String requiredText(JsonNode node, String name) {
        String value = text(node, name, null);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private String text(JsonNode node, String name, String fallback) {
        JsonNode value = node.path(name);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank()
                ? fallback
                : value.asText();
    }

    private JsonNode post(String path, Object body) throws IOException, InterruptedException {
        String url = config.platformUrl() + path;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Actor", config.platformAgentId())
                .header("X-Identity-Source", "agent")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        if (!config.platformToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.platformToken());
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Platform API returned " + response.statusCode() + ": " + response.body());
        }
        return mapper.readTree(response.body());
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
