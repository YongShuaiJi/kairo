package com.example.kairo.attach;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Demo attach executor entrypoint hosted by {@code kairo-attach-cli}. It registers with the
 * Platform, long-polls {@code ATTACH_AGENT}/{@code RELOAD_AGENT} commands, ACKs each one,
 * and exposes a {@code /health} endpoint. Migrated verbatim from the former {@code kairo-sidecar}
 * module; all environment variables, wire fields and behaviour are preserved.
 */
public class AttachExecutorServer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };

    private final ExecutorConfig config;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private volatile String executorId;
    private volatile List<Map<String, Object>> registeredTargets = List.of();
    private volatile String instanceId;
    private volatile String sidecarId;

    AttachExecutorServer(ExecutorConfig config) {
        this.config = config;
    }

    public static void main(String[] args) throws Exception {
        AttachExecutorServer executor = new AttachExecutorServer(ExecutorConfig.fromEnvironment());
        executor.registerWithPlatform();
        executor.start();
        executor.runCommandLoop();
    }

    void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(config.host(), config.port()), 64);
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "kairo-attach-executor-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/health", exchange -> write(exchange, 200, Map.of(
                "status", "UP",
                "service", "kairo-attach-executor",
                "executorId", executorId == null ? "" : executorId,
                "targetCount", registeredTargets.size(),
                "targets", registeredTargets,
                "instanceId", instanceId == null ? "" : instanceId,
                "sidecarId", sidecarId == null ? "" : sidecarId
        )));
        server.start();
        System.out.println("[kairo] Attach executor health listening on "
                + config.host() + ":" + config.port());
    }

    private void runCommandLoop() {
        while (true) {
            try {
                Map<String, Object> command = pollNextCommand();
                if ("COMMAND".equals(command.get("status"))) {
                    executeCommand(command);
                }
            } catch (Exception e) {
                Throwable failure = unwrap(e);
                System.err.println("[kairo] Attach executor polling failed: " + failure);
                failure.printStackTrace(System.err);
                sleep(1000L);
            }
        }
    }

    Map<String, Object> pollNextCommand() throws IOException, InterruptedException {
        Map<String, Object> request = Map.of(
                "waitMillis", config.pollWaitMillis(),
                "leaseSeconds", config.commandLeaseSeconds()
        );
        HttpRequest.Builder builder = platformRequest(config.platformUrl()
                        + "/api/v1/attach-executors/" + executorId + "/commands/next")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request)));
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Platform command poll failed: "
                    + response.statusCode() + " " + response.body());
        }
        return MAPPER.readValue(response.body(), MAP_TYPE);
    }

    void executeCommand(Map<String, Object> command) throws IOException, InterruptedException {
        String commandId = String.valueOf(command.get("commandId"));
        String commandType = String.valueOf(command.get("commandType"));
        String processId = text(command, "processId", config.targets().isEmpty()
                ? "1" : config.targets().get(0).processId());
        String agentJar = text(command, "agentJar", config.agentJar());
        String agentArgs = text(command, "agentArgs", "");
        Instant startedAt = Instant.now();
        try {
            if (!"ATTACH_AGENT".equals(commandType) && !"RELOAD_AGENT".equals(commandType)) {
                throw new IllegalArgumentException("Unsupported attach executor command: " + commandType);
            }
            attach(processId, agentJar, agentArgs);
            ack(commandId, "SUCCEEDED", "", Map.of(
                    "processId", processId,
                    "agentJar", agentJar,
                    "startedAt", startedAt.toString(),
                    "finishedAt", Instant.now().toString()
            ));
        } catch (Exception e) {
            Throwable failure = unwrap(e);
            ack(commandId, "FAILED", failure.getMessage() == null ? "" : failure.getMessage(), Map.of(
                    "failureType", failure.getClass().getName(),
                    "processId", processId,
                    "agentJar", agentJar,
                    "startedAt", startedAt.toString(),
                    "finishedAt", Instant.now().toString()
            ));
        }
    }

    void ack(String commandId, String status, String message, Map<String, Object> result)
            throws IOException, InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("status", status);
        request.put("message", message);
        request.put("result", result);
        HttpRequest.Builder builder = platformRequest(config.platformUrl()
                        + "/api/v1/attach-executor-commands/" + commandId + "/ack")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request)));
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Platform command ack failed: "
                    + response.statusCode() + " " + response.body());
        }
    }

    void registerWithPlatform() throws IOException, InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("executorId", config.executorId());
        request.put("executorType", config.executorType());
        request.put("hostname", config.hostname());
        request.put("endpoint", config.endpoint());
        request.put("sidecarVersion", config.sidecarVersion());
        request.put("capabilities", List.of("ATTACH_AGENT", "RELOAD_AGENT", "DISCOVER_JVM"));
        request.put("targets", config.targets().stream().map(TargetConfig::toRequest).toList());
        request.put("reason", "attach sidecar self registration");

        HttpRequest.Builder builder = platformRequest(config.platformUrl()
                        + "/api/v1/attach-sidecars/self")
                .timeout(Duration.ofSeconds(10))
                .header("X-Actor", "attach-sidecar")
                .header("X-Identity-Source", "sidecar")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request)));
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Platform sidecar registration failed: "
                    + response.statusCode() + " " + response.body());
        }
        Map<String, Object> result = MAPPER.readValue(response.body(), MAP_TYPE);
        executorId = String.valueOf(result.getOrDefault("executorId", config.executorId()));
        Object targets = result.get("targets");
        registeredTargets = targets instanceof List<?> list
                ? list.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> {
                    Map<String, Object> copy = new LinkedHashMap<>();
                    item.forEach((key, value) -> copy.put(String.valueOf(key), value));
                    return copy;
                })
                .toList()
                : List.of();
        instanceId = String.valueOf(result.getOrDefault("instanceId", ""));
        sidecarId = String.valueOf(result.getOrDefault("sidecarId", ""));
        System.out.println("[kairo] Attach executor registered executor=" + executorId
                + ", targets=" + registeredTargets.size()
                + ", firstInstance=" + instanceId
                + ", firstSidecar=" + sidecarId);
    }

    private HttpRequest.Builder platformRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(Math.max(30L, (config.pollWaitMillis() / 1000L) + 10L)))
                .header("Content-Type", "application/json");
        if (!config.platformToken().isBlank()) {
            builder.header("Authorization", "Bearer " + config.platformToken());
        }
        return builder;
    }

    void attach(String processId, String agentJar, String agentArgs) throws Exception {
        Class<?> vmType = Class.forName("com.sun.tools.attach.VirtualMachine");
        Object vm = vmType.getMethod("attach", String.class).invoke(null, processId);
        try {
            vmType.getMethod("loadAgent", String.class, String.class).invoke(vm, agentJar, agentArgs);
        } finally {
            vmType.getMethod("detach").invoke(vm);
        }
    }

    private void write(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
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

    private String text(Map<String, Object> request, String key, String fallback) {
        Object value = request.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    record ExecutorConfig(
            String host,
            int port,
            String platformUrl,
            String platformToken,
            String sidecarToken,
            String executorId,
            String executorType,
            String hostname,
            String endpoint,
            String sidecarVersion,
            String agentJar,
            long pollWaitMillis,
            long commandLeaseSeconds,
            List<TargetConfig> targets
    ) {
        static ExecutorConfig fromEnvironment() {
            String applicationName = env("KAIRO_APPLICATION_NAME", "kairo-demo");
            String targetPid = env("KAIRO_TARGET_PID", "1");
            String hostname = env("HOSTNAME", "localhost");
            String processStartId = env("KAIRO_PROCESS_START_ID",
                    applicationName + ":" + hostname + ":" + targetPid);
            String agentJar = env("KAIRO_AGENT_JAR", "/app/kairo-agent-bootstrap.jar");
            String executorId = env("KAIRO_EXECUTOR_ID", "executor-" + hostname);
            List<TargetConfig> targets = targetsFromEnvironment(applicationName, targetPid, hostname,
                    processStartId, agentJar);
            return new ExecutorConfig(
                    env("KAIRO_SIDECAR_HOST", "0.0.0.0"),
                    Integer.parseInt(env("KAIRO_SIDECAR_PORT", "18480")),
                    trimTrailingSlash(env("KAIRO_PLATFORM_URL", "http://platform:18280")),
                    env("KAIRO_PLATFORM_TOKEN", ""),
                    env("KAIRO_SIDECAR_TOKEN", ""),
                    executorId,
                    env("KAIRO_EXECUTOR_TYPE", "SIDECAR_CONTAINER"),
                    hostname,
                    env("KAIRO_SIDECAR_ENDPOINT", "http://localhost:18480"),
                    env("KAIRO_SIDECAR_VERSION", "0.1.0-SNAPSHOT"),
                    agentJar,
                    Long.parseLong(env("KAIRO_EXECUTOR_POLL_WAIT_MILLIS", "25000")),
                    Long.parseLong(env("KAIRO_EXECUTOR_COMMAND_LEASE_SECONDS", "30")),
                    targets
            );
        }

        private static List<TargetConfig> targetsFromEnvironment(String applicationName, String targetPid,
                                                                 String hostname, String processStartId,
                                                                 String agentJar) {
            String targetsJson = System.getenv("KAIRO_TARGETS_JSON");
            if (targetsJson != null && !targetsJson.isBlank()) {
                try {
                    return MAPPER.readValue(targetsJson, LIST_MAP_TYPE).stream()
                            .map(TargetConfig::fromMap)
                            .toList();
                } catch (Exception e) {
                    throw new IllegalArgumentException("Invalid KAIRO_TARGETS_JSON", e);
                }
            }
            return List.of(new TargetConfig(
                    env("KAIRO_PROJECT_NAME", "kairo"),
                    applicationName,
                    env("KAIRO_ENVIRONMENT_NAME", "SIT"),
                    env("KAIRO_INSTANCE_NICKNAME", applicationName),
                    hostname,
                    targetPid,
                    processStartId,
                    env("KAIRO_TARGET_RUNTIME", "java"),
                    env("KAIRO_TARGET_JAVA_VERSION", "unknown"),
                    agentJar
            ));
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static String trimTrailingSlash(String value) {
            return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
        }
    }

    record TargetConfig(
            String projectName,
            String applicationName,
            String environmentName,
            String nickname,
            String hostname,
            String processId,
            String processStartId,
            String runtime,
            String javaVersion,
            String agentJar
    ) {
        static TargetConfig fromMap(Map<String, Object> values) {
            String applicationName = text(values, "applicationName", "kairo-demo");
            String hostname = text(values, "hostname", env("HOSTNAME", "localhost"));
            String processId = text(values, "processId", "1");
            return new TargetConfig(
                    text(values, "projectName", "kairo"),
                    applicationName,
                    text(values, "environmentName", "SIT"),
                    text(values, "nickname", applicationName),
                    hostname,
                    processId,
                    text(values, "processStartId", applicationName + ":" + hostname + ":" + processId),
                    text(values, "runtime", "java"),
                    text(values, "javaVersion", "unknown"),
                    text(values, "agentJar", "/app/kairo-agent-bootstrap.jar")
            );
        }

        Map<String, Object> toRequest() {
            Map<String, Object> request = new LinkedHashMap<>();
            request.put("projectName", projectName);
            request.put("applicationName", applicationName);
            request.put("environmentName", environmentName);
            request.put("nickname", nickname);
            request.put("hostname", hostname);
            request.put("processId", processId);
            request.put("processStartId", processStartId);
            request.put("runtime", runtime);
            request.put("javaVersion", javaVersion);
            request.put("agentJar", agentJar);
            request.put("capabilities", List.of("ATTACH_AGENT", "RELOAD_AGENT"));
            return request;
        }

        private static String text(Map<String, Object> values, String key, String fallback) {
            Object value = values.get(key);
            return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }
    }
}
