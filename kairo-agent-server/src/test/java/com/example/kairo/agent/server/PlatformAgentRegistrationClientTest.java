package com.example.kairo.agent.server;

import com.example.kairo.agent.core.JvmInfo;
import com.example.kairo.api.protocol.KairoCommandCapabilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the real V1.7 client still emits the request accepted by a real V1.6 platform. */
class PlatformAgentRegistrationClientTest {

    @Test
    void sendsTheV17RequestCapturedAsAcceptedByTheRealV16Platform() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode fixture = mapper.readTree(Files.readAllBytes(repositoryRoot().resolve(
                "kairo-platform-server/src/test/resources/v1.7/fixtures/"
                        + "v1.7-agent-to-v1.6-platform-registration.json")));
        assertThat(fixture.path("sourceCommit").asText())
                .isEqualTo("113823b41981a2d8fb5473a772ae2d2938d9582e");
        assertThat(fixture.path("observedHttpStatus").asInt()).isEqualTo(201);

        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/agent-registrations/self", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = mapper.writeValueAsBytes(fixture.path("mockResponse"));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            AgentLaunchConfig config = AgentLaunchConfig.parse(
                    "platformUrl=http://127.0.0.1:" + server.getAddress().getPort()
                            + ",platformProjectName=cross-version-project"
                            + ",platformApplicationName=cross-version-app"
                            + ",platformProcessStartId=cross-version-start"
                            + ",platformToken=wire-token");
            PlatformAgentRegistrationClient client = new PlatformAgentRegistrationClient(config);

            PlatformAgentRegistrationClient.Registration registration = client.register(new JvmInfo(
                    "cross-version-app", 17017, "cross-version-host", "21", 1700000000000L,
                    "1.7.0", "agentmain", "ACTIVE", 0, 0, 0), 18170);

            assertThat(registration.instanceId()).isEqualTo("instance-v16-accepted-v17");
            assertThat(registration.agentId()).isEqualTo("agent-v16-accepted-v17");
            assertThat(authorization.get()).isEqualTo("Bearer wire-token");
            JsonNode sent = mapper.readTree(body.get());
            JsonNode accepted = fixture.path("request");
            Set<String> sentFields = new HashSet<>();
            sent.fieldNames().forEachRemaining(sentFields::add);
            Set<String> acceptedFields = new HashSet<>();
            accepted.fieldNames().forEachRemaining(acceptedFields::add);
            assertThat(sentFields).containsExactlyInAnyOrderElementsOf(acceptedFields);
            Iterator<String> fields = accepted.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!"capabilities".equals(field)) {
                    assertThat(sent.path(field)).as(field).isEqualTo(accepted.path(field));
                }
            }
            Set<String> capabilities = new HashSet<>();
            sent.path("capabilities").forEach(value -> capabilities.add(value.asText()));
            Set<String> acceptedCapabilities = new HashSet<>();
            accepted.path("capabilities")
                    .forEach(value -> acceptedCapabilities.add(value.asText()));
            assertThat(capabilities)
                    .contains(KairoCommandCapabilities.STRICT_NEGOTIATION, "RESET_CLASS")
                    .containsAll(KairoCommandCapabilities.V1)
                    .containsExactlyInAnyOrderElementsOf(acceptedCapabilities);
        } finally {
            server.stop(0);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null && !Files.isDirectory(current.resolve("kairo-platform-server"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root");
        }
        return current;
    }
}
