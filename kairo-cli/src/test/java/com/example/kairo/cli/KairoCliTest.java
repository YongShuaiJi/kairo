package com.example.kairo.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI integration test (V1.6 §5.4) against a JDK stub HTTP server.
 * Verifies JSON output, endpoint routing and structured error handling.
 */
class KairoCliTest {

    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private Path credsPath;
    private ByteArrayOutputStream out;
    private ByteArrayOutputStream err;
    private KairoCli cli;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        credsPath = Files.createTempFile("kairo-creds", ".json");
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        cli = new KairoCli(new PrintStream(out), new PrintStream(err), credsPath);
        writeCreds();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        try {
            Files.deleteIfExists(credsPath);
        } catch (IOException ignored) {
        }
    }

    private void writeCreds() throws IOException {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        Files.writeString(credsPath, mapper.writeValueAsString(Map.of("baseUrl", baseUrl, "token", "test-token")));
    }

    private void stub(String path, String method, Handler handler) {
        server.createContext(path, exchange -> {
            if (!exchange.getRequestMethod().equals(method)) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                handler.handle(exchange, new String(body, StandardCharsets.UTF_8));
            } catch (IOException e) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = new ObjectMapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().close();
    }

    @Test
    void loginWritesCredentialsFile() {
        int code = cli.run(new String[]{"login", "--base-url", "http://example.com", "--token", "secret"});
        assertThat(code).isEqualTo(0);
        String output = out.toString(StandardCharsets.UTF_8).trim();
        assertThat(output).contains("\"ok\":true");
        assertThat(credsPath).content().contains("http://example.com").contains("secret");
    }

    @Test
    void whoamiPrintsSubjectJson() {
        stub("/api/v1/auth/me", "GET", (ex, body) -> writeJson(ex, 200,
                Map.of("subject", "alice", "roles", List.of("ADMIN"))));
        int code = cli.run(new String[]{"whoami"});
        assertThat(code).isEqualTo(0);
        String output = out.toString(StandardCharsets.UTF_8).trim();
        assertThat(output).contains("\"subject\":\"alice\"");
        assertThat(output).contains("\"roles\"");
    }

    @Test
    void discoverCallsTargetsEndpoint() {
        stub("/api/v1/targets", "GET", (ex, body) -> {
            String q = ex.getRequestURI().getQuery();
            assertThat(q).contains("applicationId=app1");
            assertThat(q).contains("environmentId=prod");
            writeJson(ex, 200, List.of(Map.of("id", "t1", "name", "Target1")));
        });
        int code = cli.run(new String[]{"discover", "--app", "app1", "--env", "prod"});
        assertThat(code).isEqualTo(0);
        String output = out.toString(StandardCharsets.UTF_8).trim();
        assertThat(output).contains("\"id\":\"t1\"");
    }

    @Test
    void error404PrintsStructuredJsonToStderrWithNonZeroExit() {
        server.createContext("/api/v1/operations/missing", exchange -> {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(405, -1);
                exchange.close();
                return;
            }
            String body = "{\"code\":\"RESOURCE_NOT_FOUND\",\"message\":\"Not found\",\"category\":\"NOT_FOUND\",\"retryable\":false}";
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.getResponseBody().close();
        });
        int code = cli.run(new String[]{"observe", "--operation", "missing"});
        assertThat(code).isNotEqualTo(0);
        String errorOut = err.toString(StandardCharsets.UTF_8).trim();
        assertThat(errorOut).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
        assertThat(errorOut).contains("\"category\":\"NOT_FOUND\"");
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange, String body) throws IOException;
    }
}
