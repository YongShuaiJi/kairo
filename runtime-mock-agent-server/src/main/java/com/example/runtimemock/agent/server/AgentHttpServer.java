package com.example.runtimemock.agent.server;

import com.example.runtimemock.agent.core.AgentRuntime;
import com.example.runtimemock.agent.core.MethodInfo;
import com.example.runtimemock.api.InvokePhase;
import com.example.runtimemock.api.MethodSelector;
import com.example.runtimemock.api.MockRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AgentHttpServer implements AutoCloseable {

    public static final String PROTOCOL_VERSION = "v1";

    private final AgentRuntime runtime;
    private final String token;
    private final HttpServer server;
    private final ExecutorService executor;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    public AgentHttpServer(AgentRuntime runtime, String host, int port, String token) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.token = token == null ? "" : token;
        try {
            this.server = HttpServer.create(new InetSocketAddress(host == null ? "127.0.0.1" : host, port), 64);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start agent HTTP server", e);
        }
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "runtime-mock-agent-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        registerContexts();
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private void registerContexts() {
        server.createContext("/", authenticated(this::route));
    }

    private HttpHandler authenticated(ExchangeHandler handler) {
        return exchange -> {
            try {
                if (!isHealthPath(exchange.getRequestURI().getPath()) && !authorized(exchange)) {
                    write(exchange, 401, Map.of("error", "Unauthorized"));
                    return;
                }
                handler.handle(exchange);
            } catch (IllegalArgumentException e) {
                write(exchange, 400, Map.of("error", e.getMessage()));
            } catch (Exception e) {
                write(exchange, 500, Map.of("error", e.getClass().getName(), "message", e.getMessage()));
            } finally {
                exchange.close();
            }
        };
    }

    private void route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getPath();
        String path = normalizePath(rawPath);
        if ("GET".equals(method) && "/health".equals(path)) {
            write(exchange, 200, Map.of("status", "UP", "protocolVersion", PROTOCOL_VERSION));
            return;
        }
        if ("GET".equals(method) && "/status".equals(path)) {
            write(exchange, 200, Map.of(
                    "jvm", runtime.jvmInfo(),
                    "metrics", runtime.metrics(),
                    "protocolVersion", PROTOCOL_VERSION
            ));
            return;
        }
        if ("GET".equals(method) && "/jvm".equals(path)) {
            write(exchange, 200, runtime.jvmInfo());
            return;
        }
        if ("GET".equals(method) && "/classes".equals(path)) {
            Map<String, String> query = query(exchange.getRequestURI());
            write(exchange, 200, runtime.searchClasses(query.get("keyword"), parseInt(query.get("limit"), 100)));
            return;
        }
        if ("GET".equals(method) && path.startsWith("/classes/") && path.endsWith("/methods")) {
            String classId = segment(path, "/classes/", "/methods");
            write(exchange, 200, runtime.methods(classId));
            return;
        }
        if ("POST".equals(method) && "/scripts/compile".equals(path)) {
            JsonNode body = readJson(exchange);
            String ruleId = text(body, "ruleId", "compile-check");
            long version = body.path("version").asLong(1L);
            String script = requiredText(body, "script");
            write(exchange, 200, Map.of(
                    "ruleId", ruleId,
                    "version", version,
                    "scriptHash", runtime.compileScript(ruleId, version, script).scriptHash()
            ));
            return;
        }
        if ("GET".equals(method) && "/rules".equals(path)) {
            write(exchange, 200, runtime.rules());
            return;
        }
        if ("POST".equals(method) && "/rules".equals(path)) {
            RuleRequest request = readRuleRequest(exchange);
            MockRule rule = request.toRule(runtime.methods(request.classId()));
            runtime.publish(request.classId(), rule, actor(exchange));
            write(exchange, 201, runtime.rules());
            return;
        }
        if ("PUT".equals(method) && path.startsWith("/rules/")) {
            String ruleId = lastSegment(path);
            RuleRequest request = readRuleRequest(exchange);
            MockRule rule = request.toRule(runtime.methods(request.classId())).toBuilder().id(ruleId).build();
            runtime.publish(request.classId(), rule, actor(exchange));
            write(exchange, 200, runtime.rules());
            return;
        }
        if ("POST".equals(method) && path.startsWith("/rules/") && path.endsWith("/enable")) {
            runtime.setEnabled(segment(path, "/rules/", "/enable"), true, actor(exchange));
            write(exchange, 200, runtime.rules());
            return;
        }
        if ("POST".equals(method) && path.startsWith("/rules/") && path.endsWith("/disable")) {
            runtime.setEnabled(segment(path, "/rules/", "/disable"), false, actor(exchange));
            write(exchange, 200, runtime.rules());
            return;
        }
        if ("DELETE".equals(method) && path.startsWith("/rules/")) {
            runtime.remove(lastSegment(path), actor(exchange));
            write(exchange, 200, runtime.rules());
            return;
        }
        if ("POST".equals(method) && "/agent/disable-all".equals(path)) {
            runtime.disableAll(true);
            write(exchange, 200, runtime.metrics());
            return;
        }
        if ("POST".equals(method) && "/agent/enable-all".equals(path)) {
            runtime.disableAll(false);
            write(exchange, 200, runtime.metrics());
            return;
        }
        if ("POST".equals(method) && "/agent/reset-all".equals(path)) {
            runtime.resetAll(actor(exchange));
            write(exchange, 200, runtime.metrics());
            return;
        }
        if ("POST".equals(method) && "/agent/reset-class".equals(path)) {
            JsonNode body = readJson(exchange);
            String classId = text(body, "classId", text(body, "className", null));
            write(exchange, 200, runtime.resetClass(classId, actor(exchange)));
            return;
        }
        if ("POST".equals(method) && "/agent/shutdown".equals(path)) {
            runtime.recordEvent("agent.shutdown.requested", actor(exchange), null, null,
                    "Shutdown requested through local API");
            write(exchange, 200, Map.of("status", "SHUTTING_DOWN"));
            shutdownAsync();
            return;
        }
        if ("GET".equals(method) && "/events".equals(path)) {
            write(exchange, 200, runtime.events());
            return;
        }
        if ("GET".equals(method) && "/metrics".equals(path)) {
            write(exchange, 200, runtime.metrics());
            return;
        }
        write(exchange, 404, Map.of("error", "Not found", "path", path));
    }

    private boolean authorized(HttpExchange exchange) {
        if (token.isBlank()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("X-Agent-Token");
        if (token.equals(header)) {
            return true;
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        return ("Bearer " + token).equals(authorization);
    }

    private boolean isHealthPath(String path) {
        return "/health".equals(path) || "/v1/health".equals(path);
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        if ("/v1".equals(path)) {
            return "/";
        }
        if (path.startsWith("/v1/")) {
            return path.substring(3);
        }
        return path;
    }

    private RuleRequest readRuleRequest(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        return RuleRequest.from(body);
    }

    private JsonNode readJson(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            byte[] body = inputStream.readAllBytes();
            if (body.length == 0) {
                return objectMapper.createObjectNode();
            }
            JsonNode node = objectMapper.readTree(body);
            return node == null ? objectMapper.createObjectNode() : node;
        }
    }

    private void shutdownAsync() {
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(100L);
                runtime.close();
                close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new UncheckedIOException(new IOException(e));
            }
        }, "runtime-mock-agent-shutdown");
        thread.setDaemon(true);
        thread.start();
    }

    private void write(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("X-Runtime-Mock-Protocol", PROTOCOL_VERSION);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, json.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(json);
        }
    }

    private String actor(HttpExchange exchange) {
        String actor = exchange.getRequestHeaders().getFirst("X-Actor");
        return actor == null || actor.isBlank() ? "api" : actor;
    }

    private Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return values;
        }
        for (String part : query.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Integer.parseInt(value);
    }

    private String requiredText(JsonNode body, String field) {
        String value = text(body, field, null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private String text(JsonNode body, String field, String defaultValue) {
        JsonNode node = body.path(field);
        return node.isMissingNode() || node.isNull() ? defaultValue : node.asText();
    }

    private String lastSegment(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? path : decode(path.substring(index + 1));
    }

    private String segment(String path, String prefix, String suffix) {
        return decode(path.substring(prefix.length(), path.length() - suffix.length()));
    }

    @FunctionalInterface
    private interface ExchangeHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    public record RuleRequest(
            String id,
            long version,
            String name,
            String description,
            String classId,
            String className,
            String classLoaderId,
            String methodName,
            String methodDescriptor,
            InvokePhase phase,
            String script,
            int priority,
            int percentage,
            long maxHits,
            long expireAt,
            boolean failOpen,
            boolean enabled
    ) {
        static RuleRequest from(JsonNode body) {
            String id = textValue(body, "id", "rule-" + System.currentTimeMillis());
            return new RuleRequest(
                    id,
                    body.path("version").asLong(1L),
                    textValue(body, "name", id),
                    textValue(body, "description", null),
                    required(body, "classId"),
                    required(body, "className"),
                    required(body, "classLoaderId"),
                    required(body, "methodName"),
                    required(body, "methodDescriptor"),
                    InvokePhase.valueOf(textValue(body, "phase", "BEFORE")),
                    required(body, "script"),
                    body.path("priority").asInt(0),
                    body.path("percentage").asInt(100),
                    body.path("maxHits").asLong(0L),
                    body.path("expireAt").asLong(0L),
                    !body.has("failOpen") || body.path("failOpen").asBoolean(true),
                    !body.has("enabled") || body.path("enabled").asBoolean(true)
            );
        }

        MockRule toRule(List<MethodInfo> ignored) {
            return MockRule.builder()
                    .id(id)
                    .version(version)
                    .name(name)
                    .description(description)
                    .target(MethodSelector.builder()
                            .className(className)
                            .classLoaderId(classLoaderId)
                            .methodName(methodName)
                            .methodDescriptor(methodDescriptor)
                            .build())
                    .phase(phase)
                    .script(script)
                    .priority(priority)
                    .percentage(percentage)
                    .maxHits(maxHits)
                    .expireAt(expireAt)
                    .failOpen(failOpen)
                    .enabled(enabled)
                    .build();
        }

        private static String required(JsonNode body, String field) {
            String value = textValue(body, field, null);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return value;
        }

        private static String textValue(JsonNode body, String field, String defaultValue) {
            JsonNode node = body.path(field);
            return node.isMissingNode() || node.isNull() ? defaultValue : node.asText();
        }
    }
}
