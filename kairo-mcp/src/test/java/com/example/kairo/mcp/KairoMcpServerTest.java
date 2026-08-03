package com.example.kairo.mcp;

import com.example.kairo.sdk.KairoClient;
import com.example.kairo.sdk.KairoClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KairoMcpServer} (V1.6 §5.4). Drives the server via
 * {@link KairoMcpServer#handle(String)} so no real stdio is needed.
 */
class KairoMcpServerTest {

    private HttpServer server;
    private KairoMcpServer mcpServer;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.start();
        KairoClient client = new KairoClient(
                new KairoClientConfig("http://127.0.0.1:" + port, "test-token")
                        .source("mcp"));
        mcpServer = new KairoMcpServer(client);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void initializeReturnsProtocolVersionAndCapabilities() throws Exception {
        String response = mcpServer.handle(request(1, "initialize", Map.of()));
        Map<String, Object> result = result(response);
        assertThat(result.get("protocolVersion")).isEqualTo("2024-11-05");
        Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        assertThat(serverInfo.get("name")).isEqualTo("kairo-mcp");
        // V1.7 M5-A §12.1: initialize.serverInfo.version uses the shared build-version resolver.
        assertThat(serverInfo.get("version")).isEqualTo(com.example.kairo.api.build.KairoBuildVersion.resolve());
        Map<String, Object> caps = (Map<String, Object>) result.get("capabilities");
        assertThat(caps).containsKey("tools");
    }

    @Test
    void versionBannerUsesSharedBuildVersionResolver() {
        // V1.7 M5-A §12.1: kairo-mcp --version reports the packaged project version without credentials.
        assertThat(KairoMcpServer.versionBanner())
                .isEqualTo("kairo-mcp " + com.example.kairo.api.build.KairoBuildVersion.resolve());
    }

    @Test
    void toolsListReturnsAllToolsWithSchemas() throws Exception {
        String response = mcpServer.handle(request(2, "tools/list", Map.of()));
        Map<String, Object> result = result(response);
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertThat(tools).hasSize(10);

        Map<String, Object> trial = tools.stream()
                .filter(t -> "trial_enhancement".equals(t.get("name")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> schema = (Map<String, Object>) trial.get("inputSchema");
        List<String> required = (List<String>) schema.get("required");
        assertThat(required).contains("previewToken", "previewRevision");
    }

    @Test
    void toolsCallWhoamiCallsStubClient() throws Exception {
        server.createContext("/api/v1/auth/me", exchange -> {
            writeJson(exchange, 200, Map.of("subject", "alice", "roles", List.of("USER")));
        });

        String response = mcpServer.handle(request(3, "tools/call", Map.of(
                "name", "whoami",
                "arguments", Map.of()
        )));
        Map<String, Object> result = result(response);
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        String text = (String) content.get(0).get("text");
        Map<String, Object> body = mapper.readValue(text, Map.class);
        assertThat(body.get("subject")).isEqualTo("alice");
    }

    @Test
    void toolsCallTrialEnhancementWithoutPreviewTokenReturnsPreviewRequired() throws Exception {
        String response = mcpServer.handle(request(4, "tools/call", Map.of(
                "name", "trial_enhancement",
                "arguments", Map.of(
                        "sessionId", "s-1",
                        "target", Map.of("id", "t-1"),
                        "script", "println 1"
                )
        )));
        Map<String, Object> result = result(response);
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        String text = (String) content.get(0).get("text");
        Map<String, Object> body = mapper.readValue(text, Map.class);
        assertThat(body.get("error")).isEqualTo("PREVIEW_REQUIRED");
    }

    @Test
    void unknownMethodReturnsError() throws Exception {
        String response = mcpServer.handle(request(5, "unknown/method", Map.of()));
        Map<String, Object> error = error(response);
        assertThat(error.get("code")).isEqualTo(-32601);
    }

    private String request(Object id, String method, Map<String, Object> params) throws Exception {
        return mapper.writeValueAsString(Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "method", method,
                "params", params
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(String response) throws Exception {
        Map<String, Object> map = mapper.readValue(response, Map.class);
        return (Map<String, Object>) map.get("result");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> error(String response) throws Exception {
        Map<String, Object> map = mapper.readValue(response, Map.class);
        return (Map<String, Object>) map.get("error");
    }

    private static void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] payload = new ObjectMapper().writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().close();
    }
}
