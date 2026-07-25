package com.example.kairo.attach;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Compatibility gate for the demo attach executor migrated from the former {@code kairo-sidecar}
 * module into {@code kairo-attach-cli}. Drives a stubbed executor against an in-process fake
 * Platform and asserts that registration, polling, execution, ACK, health and config flow are
 * preserved. {@code attach()} is overridden so no real JVM attach occurs.
 */
class AttachExecutorCompatibilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private HttpServer fakePlatform;
    private int fakePlatformPort;

    private final AtomicReference<Map<String, Object>> receivedRegistration = new AtomicReference<>();
    private final AtomicReference<Map<String, Object>> nextCommand = new AtomicReference<>();
    private final CopyOnWriteArrayList<Map<String, Object>> receivedAcks = new CopyOnWriteArrayList<>();

    private StubExecutor executor;

    @BeforeEach
    void startFakePlatform() throws IOException {
        fakePlatform = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakePlatform.createContext("/api/v1/attach-sidecars/self", this::handleRegistration);
        fakePlatform.createContext("/api/v1/attach-executors/", this::handleCommandsNext);
        fakePlatform.createContext("/api/v1/attach-executor-commands/", this::handleAck);
        fakePlatform.setExecutor(null);
        fakePlatform.start();
        fakePlatformPort = fakePlatform.getAddress().getPort();

        AttachExecutorServer.ExecutorConfig config = new AttachExecutorServer.ExecutorConfig(
                "127.0.0.1", freePort(),
                "http://127.0.0.1:" + fakePlatformPort,
                "platform-token", "sidecar-token",
                "exec-test", "SIDECAR_CONTAINER", "host1", "http://exec:18480", "0.1.0-SNAPSHOT",
                "/app/kairo-agent-bootstrap.jar", 50L, 30L,
                List.of(new AttachExecutorServer.TargetConfig(
                        "kairo", "kairo-demo", "SIT", "nick", "host1", "1",
                        "kairo-demo:host1:1", "java", "21", "/app/kairo-agent-bootstrap.jar")));
        executor = new StubExecutor(config);
    }

    @AfterEach
    void stopFakePlatform() {
        if (fakePlatform != null) {
            fakePlatform.stop(0);
        }
    }

    @Test
    void registersWithPlatformWithExpectedPayload() throws Exception {
        executor.registerWithPlatform();

        Map<String, Object> body = receivedRegistration.get();
        assertThat(body).isNotNull();
        assertThat(body.get("executorId")).isEqualTo("exec-test");
        assertThat(body.get("executorType")).isEqualTo("SIDECAR_CONTAINER");
        assertThat(body.get("endpoint")).isEqualTo("http://exec:18480");
        assertThat(body.get("sidecarVersion")).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(body.get("capabilities")).isEqualTo(List.of("ATTACH_AGENT", "RELOAD_AGENT", "DISCOVER_JVM"));
        assertThat(body.get("reason")).isEqualTo("attach sidecar self registration");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> targets = (List<Map<String, Object>>) body.get("targets");
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).get("projectName")).isEqualTo("kairo");
        assertThat(targets.get(0).get("capabilities")).isEqualTo(List.of("ATTACH_AGENT", "RELOAD_AGENT"));
    }

    @Test
    void exposesHealthEndpointAfterStart() throws Exception {
        executor.registerWithPlatform();
        executor.start();

        String health = httpGet("http://127.0.0.1:" + executorHealthPort() + "/health");
        @SuppressWarnings("unchecked")
        Map<String, Object> body = MAPPER.readValue(health, Map.class);
        assertThat(body.get("status")).isEqualTo("UP");
        assertThat(body.get("service")).isEqualTo("kairo-attach-executor");
        assertThat(body.get("executorId")).isEqualTo("exec-test");
        assertThat(body.get("instanceId")).isEqualTo("inst-1");
        assertThat(body.get("sidecarId")).isEqualTo("sc-1");
        assertThat(body.get("targetCount")).isEqualTo(0);
    }

    @Test
    void pollsNextCommand() throws Exception {
        executor.registerWithPlatform();
        nextCommand.set(command("cmd-1", "ATTACH_AGENT"));

        Map<String, Object> polled = executor.pollNextCommand();
        assertThat(polled.get("status")).isEqualTo("COMMAND");
        assertThat(polled.get("commandId")).isEqualTo("cmd-1");
        assertThat(polled.get("commandType")).isEqualTo("ATTACH_AGENT");
    }

    @Test
    void executesAttachAgentAndAcksSucceeded() throws Exception {
        executor.registerWithPlatform();
        executor.executeCommand(command("cmd-ok", "ATTACH_AGENT"));

        assertThat(executor.attachCalls).isEqualTo(1);
        assertThat(receivedAcks).hasSize(1);
        Map<String, Object> ack = receivedAcks.get(0);
        assertThat(ack.get("status")).isEqualTo("SUCCEEDED");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) ack.get("result");
        assertThat(result.get("processId")).isEqualTo("1");
        assertThat(result.get("agentJar")).isEqualTo("/app/kairo-agent-bootstrap.jar");
        assertThat(result.get("startedAt")).isNotNull();
        assertThat(result.get("finishedAt")).isNotNull();
    }

    @Test
    void executesReloadAgentAndAcksSucceeded() throws Exception {
        executor.registerWithPlatform();
        executor.executeCommand(command("cmd-reload", "RELOAD_AGENT"));

        assertThat(executor.attachCalls).isEqualTo(1);
        assertThat(receivedAcks).hasSize(1);
        Map<String, Object> ack = receivedAcks.get(0);
        assertThat(ack.get("status")).isEqualTo("SUCCEEDED");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) ack.get("result");
        assertThat(result.get("processId")).isEqualTo("1");
        assertThat(result.get("agentJar")).isEqualTo("/app/kairo-agent-bootstrap.jar");
    }

    @Test
    void rejectsUnsupportedCommandAndAcksFailed() throws Exception {
        executor.registerWithPlatform();
        executor.executeCommand(command("cmd-bad", "PING"));

        assertThat(executor.attachCalls).isZero();
        assertThat(receivedAcks).hasSize(1);
        Map<String, Object> ack = receivedAcks.get(0);
        assertThat(ack.get("status")).isEqualTo("FAILED");
        assertThat(String.valueOf(ack.get("message"))).contains("Unsupported attach executor command");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) ack.get("result");
        assertThat(result.get("failureType")).isEqualTo(IllegalArgumentException.class.getName());
    }

    @Test
    void configCarriesAllExecutorFields() {
        AttachExecutorServer.ExecutorConfig config = executor.config;
        assertThat(config.executorId()).isEqualTo("exec-test");
        assertThat(config.executorType()).isEqualTo("SIDECAR_CONTAINER");
        assertThat(config.platformUrl()).isEqualTo("http://127.0.0.1:" + fakePlatformPort);
        assertThat(config.platformToken()).isEqualTo("platform-token");
        assertThat(config.agentJar()).isEqualTo("/app/kairo-agent-bootstrap.jar");
        assertThat(config.pollWaitMillis()).isEqualTo(50L);
        assertThat(config.commandLeaseSeconds()).isEqualTo(30L);
        assertThat(config.targets()).hasSize(1);
        assertThat(config.targets().get(0).processStartId()).isEqualTo("kairo-demo:host1:1");
    }

    // --- fake Platform handlers -------------------------------------------------

    private void handleRegistration(HttpExchange exchange) throws IOException {
        receivedRegistration.set(readBody(exchange));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("executorId", "exec-test");
        response.put("targets", List.of());
        response.put("instanceId", "inst-1");
        response.put("sidecarId", "sc-1");
        writeJson(exchange, 200, response);
    }

    private void handleCommandsNext(HttpExchange exchange) throws IOException {
        readBody(exchange);
        Map<String, Object> command = nextCommand.get();
        Map<String, Object> response = command != null ? command : Map.of("status", "IDLE");
        writeJson(exchange, 200, response);
    }

    private void handleAck(HttpExchange exchange) throws IOException {
        receivedAcks.add(readBody(exchange));
        writeJson(exchange, 200, Map.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readAllBytes();
        if (bytes.length == 0) {
            return new LinkedHashMap<>();
        }
        return MAPPER.readValue(bytes, Map.class);
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private Map<String, Object> command(String id, String type) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("status", "COMMAND");
        command.put("commandId", id);
        command.put("commandType", type);
        command.put("processId", "1");
        command.put("agentJar", "/app/kairo-agent-bootstrap.jar");
        command.put("agentArgs", "");
        return command;
    }

    private String httpGet(String url) throws IOException, InterruptedException {
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpResponse<String> response = client.send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private int executorHealthPort() {
        return executor.config.port();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** Stub executor: overrides attach() so no real JVM attach happens. */
    static final class StubExecutor extends AttachExecutorServer {
        final AttachExecutorServer.ExecutorConfig config;
        int attachCalls;

        StubExecutor(AttachExecutorServer.ExecutorConfig config) {
            super(config);
            this.config = config;
        }

        @Override
        void attach(String processId, String agentJar, String agentArgs) {
            attachCalls++;
        }
    }
}
