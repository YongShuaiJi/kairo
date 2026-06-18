package com.example.runtimemock.control;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ControlHttpServer implements AutoCloseable {

    private final ControlServerOptions options;
    private final HttpServer server;
    private final ExecutorService executor;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ControlPlaneService controlPlane = new ControlPlaneService();

    public ControlHttpServer(ControlServerOptions options) {
        this.options = options;
        try {
            this.server = HttpServer.create(new InetSocketAddress(options.host(), options.port()), 64);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start control server", e);
        }
        this.executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "runtime-mock-control-http");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        server.createContext("/", route());
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

    private HttpHandler route() {
        return exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (!isPublicPath(path) && !authorized(exchange)) {
                    writeJson(exchange, 401, errorBody(exchange, "UNAUTHORIZED",
                            "A valid control token is required", Map.of(), false));
                    return;
                }
                if (path.startsWith("/api/v1/") && handleControlApi(exchange, path)) {
                    return;
                }
                if (path.startsWith("/api/")) {
                    proxy(exchange);
                    return;
                }
                serveStatic(exchange, path);
            } catch (ControlPlaneException e) {
                writeJson(exchange, e.status(), errorBody(exchange, e.code(), e.getMessage(), e.details(), e.retryable()));
            } catch (Exception e) {
                writeJson(exchange, 500, errorBody(exchange, "INTERNAL_ERROR",
                        "The control server could not complete the request", Map.of(), false));
            } finally {
                exchange.close();
            }
        };
    }

    private boolean handleControlApi(HttpExchange exchange, String path) throws IOException {
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/api/v1/control/health".equals(path)) {
            writeJson(exchange, 200, controlPlane.health());
            return true;
        }
        if ("GET".equals(method) && "/api/v1/audits".equals(path)) {
            writeJson(exchange, 200, controlPlane.audits());
            return true;
        }
        if ("GET".equals(method) && "/api/v1/recording-sessions".equals(path)) {
            writeJson(exchange, 200, controlPlane.recordingSessions());
            return true;
        }
        if ("POST".equals(method) && "/api/v1/recording-sessions".equals(path)) {
            writeJson(exchange, 201, controlPlane.createRecordingSession(readJsonMap(exchange)));
            return true;
        }
        String recordingSessionId = pathVariable(path, "/api/v1/recording-sessions/", "/transition");
        if ("POST".equals(method) && recordingSessionId != null) {
            writeJson(exchange, 200, controlPlane.transitionRecordingSession(recordingSessionId, readJsonMap(exchange)));
            return true;
        }
        if ("GET".equals(method) && "/api/v1/datasets".equals(path)) {
            writeJson(exchange, 200, controlPlane.datasetVersions());
            return true;
        }
        if ("POST".equals(method) && "/api/v1/datasets".equals(path)) {
            writeJson(exchange, 201, controlPlane.createDatasetVersion(readJsonMap(exchange)));
            return true;
        }
        if ("GET".equals(method) && "/api/v1/replay-plans".equals(path)) {
            writeJson(exchange, 200, controlPlane.replayPlans());
            return true;
        }
        if ("POST".equals(method) && "/api/v1/replay-plans".equals(path)) {
            writeJson(exchange, 201, controlPlane.createReplayPlan(readJsonMap(exchange)));
            return true;
        }
        String replayPlanId = pathVariable(path, "/api/v1/replay-plans/", "/transition");
        if ("POST".equals(method) && replayPlanId != null) {
            writeJson(exchange, 200, controlPlane.transitionReplayPlan(replayPlanId, readJsonMap(exchange)));
            return true;
        }
        return false;
    }

    private void proxy(HttpExchange exchange) throws Exception {
        URI agent = options.defaultAgent();
        String token = options.defaultToken();
        String targetPath = exchange.getRequestURI().getPath().substring("/api".length());
        String rawQuery = removeProxyQuery(exchange.getRequestURI().getRawQuery());
        URI target = agent.resolve(targetPath + (rawQuery.isBlank() ? "" : "?" + rawQuery));

        byte[] body = readAll(exchange.getRequestBody());
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10))
                .method(exchange.getRequestMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        if (!token.isBlank()) {
            requestBuilder.header("X-Agent-Token", token);
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null) {
            requestBuilder.header("Content-Type", contentType);
        }
        HttpResponse<byte[]> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofByteArray());
        response.headers().firstValue("Content-Type")
                .ifPresent(value -> exchange.getResponseHeaders().set("Content-Type", value));
        write(exchange, response.statusCode(), exchange.getResponseHeaders().getFirst("Content-Type"), response.body());
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        String resource = "/web" + ("/".equals(path) ? "/index.html" : path);
        try (InputStream inputStream = ControlHttpServer.class.getResourceAsStream(resource)) {
            if (inputStream == null) {
                write(exchange, HttpURLConnection.HTTP_NOT_FOUND, "text/plain; charset=utf-8",
                        "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (resource.endsWith("/index.html")) {
                exchange.getResponseHeaders().add("Set-Cookie",
                        "runtime_mock_control=" + options.controlToken()
                                + "; Path=/; HttpOnly; SameSite=Strict");
            }
            write(exchange, 200, contentType(resource), readAll(inputStream));
        }
    }

    private String removeProxyQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String part : rawQuery.split("&")) {
            String key = part.contains("=") ? part.substring(0, part.indexOf('=')) : part;
            String decodedKey = decode(key);
            if ("agent".equals(decodedKey) || "token".equals(decodedKey)) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('&');
            }
            builder.append(part);
        }
        return builder.toString();
    }

    private Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            String value = separator < 0 ? "" : part.substring(separator + 1);
            values.put(decode(key), decode(value));
        }
        return values;
    }

    private boolean isPublicPath(String path) {
        return "/".equals(path)
                || "/index.html".equals(path)
                || path.startsWith("/styles")
                || path.startsWith("/app.")
                || "/api/v1/control/health".equals(path);
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("X-Control-Token");
        if (options.controlToken().equals(header)) {
            return true;
        }
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) {
            return false;
        }
        for (String item : cookie.split(";")) {
            String[] pair = item.trim().split("=", 2);
            if (pair.length == 2
                    && "runtime_mock_control".equals(pair[0])
                    && options.controlToken().equals(pair[1])) {
                return true;
            }
        }
        return false;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private byte[] readAll(InputStream inputStream) throws IOException {
        try (inputStream) {
            return inputStream.readAllBytes();
        }
    }

    private Map<String, Object> readJsonMap(HttpExchange exchange) throws IOException {
        byte[] body = readAll(exchange.getRequestBody());
        if (body.length == 0) {
            return Map.of();
        }
        return objectMapper.readValue(body, new TypeReference<>() {
        });
    }

    private void writeJson(HttpExchange exchange, int status, Object value) throws IOException {
        write(exchange, status, "application/json; charset=utf-8", objectMapper.writeValueAsBytes(value));
    }

    private Map<String, Object> errorBody(HttpExchange exchange, String code, String message,
                                          Map<String, Object> details, boolean retryable) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message);
        body.put("correlationId", exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
        body.put("details", details);
        body.put("retryable", retryable);
        return body;
    }

    private void write(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType == null ? "application/octet-stream" : contentType);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(body);
        }
    }

    private String contentType(String resource) {
        if (resource.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        return "application/octet-stream";
    }

    private String pathVariable(String path, String prefix, String suffix) {
        if (!path.startsWith(prefix) || !path.endsWith(suffix)) {
            return null;
        }
        String encoded = path.substring(prefix.length(), path.length() - suffix.length());
        if (encoded.isBlank() || encoded.contains("/")) {
            return null;
        }
        return decode(encoded);
    }
}
