package com.example.runtimemock.agent.server;

import com.example.runtimemock.agent.core.JvmInfo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PlatformAgentRegistrationClient {

    private final AgentLaunchConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    PlatformAgentRegistrationClient(AgentLaunchConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    Registration register(JvmInfo jvmInfo, int listenPort) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            if (config.platformApplicationId() != null) {
                body.put("applicationId", config.platformApplicationId());
            } else {
                body.put("projectName", config.platformProjectName());
                body.put("applicationName", config.platformApplicationName());
            }
            if (config.platformEnvironmentId() != null) {
                body.put("environmentId", config.platformEnvironmentId());
            }
            if (config.platformEnvironmentName() != null) {
                body.put("environmentName", config.platformEnvironmentName());
            }
            if (config.platformInstanceId() != null) {
                body.put("instanceId", config.platformInstanceId());
            }
            if (config.platformNickname() != null) {
                body.put("nickname", config.platformNickname());
            }
            body.put("hostname", jvmInfo.host());
            body.put("processId", String.valueOf(jvmInfo.pid()));
            String processStartId = config.platformProcessStartId() == null
                    ? jvmInfo.host() + ":" + jvmInfo.pid() + ":" + jvmInfo.startTimeMillis()
                    : config.platformProcessStartId();
            body.put("processStartId", processStartId);
            body.put("jvmStartedAtEpochMillis", jvmInfo.startTimeMillis());
            body.put("runtime", "java-" + jvmInfo.javaVersion());
            body.put("javaVersion", jvmInfo.javaVersion());
            body.put("agentVersion", jvmInfo.agentVersion());
            body.put("loadMode", jvmInfo.loadMode());
            body.put("listenHost", config.host());
            body.put("listenPort", listenPort);
            body.put("capabilities", List.of(
                    "BYTECODE_TRANSFORM",
                    "DISCOVER_TARGETS",
                    "APPLY_RULE",
                    "RESET_CLASS",
                    "RESET_ALL",
                    "RECORD_INVOCATIONS"
            ));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(
                            config.platformUrl() + "/api/v1/agent-registrations/self"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            if (!config.platformToken().isBlank()) {
                builder.header("Authorization", "Bearer " + config.platformToken());
            }
            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Platform registration returned "
                        + response.statusCode() + ": " + response.body());
            }
            JsonNode result = mapper.readTree(response.body());
            return new Registration(required(result, "instanceId", "instance_id"),
                    required(result, "agentId", "agent_id"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Platform registration was interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot register runtime mock agent with platform", e);
        }
    }

    private String required(JsonNode result, String camelCase, String snakeCase) {
        String value = result.path(camelCase).asText(result.path(snakeCase).asText());
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Platform registration response is missing " + camelCase);
        }
        return value;
    }

    record Registration(String instanceId, String agentId) {
    }
}
