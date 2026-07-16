package com.example.kairo.platform.command;

import com.example.kairo.agent.server.protocol.AgentProtocolInfo;
import com.example.kairo.api.protocol.KairoCommandCapabilities;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Production HTTP + H2 evidence for V1.6/V1.7 Agent protocol negotiation. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_v17_protocol;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentProtocolNegotiationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void legacyV16RegistrationWithoutProtocolVersionsCanReceiveV1Command() throws Exception {
        String agentId = register("legacy", null, List.of("JAVA_METHOD"));

        JsonNode command = enqueue(agentId, "legacy");
        JsonNode polled = postOk("/api/v1/agents/" + agentId + "/commands/next",
                Map.of("leaseSeconds", 30));

        assertThat(polled.path("id").asText()).isEqualTo(command.path("id").asText());
        assertThat(polled.path("command_type").asText()).isEqualTo("RESET_CLASS");
    }

    @Test
    void exactV16AdvertisementCanRegisterAndReceiveACommandFromV17Platform() throws Exception {
        AgentProtocolInfo v16 = AgentProtocolInfo.defaultV1();
        String agentId = register("exact-v16", v16.protocolVersions(),
                new ArrayList<>(v16.capabilities()));

        JsonNode command = enqueue(agentId, "exact-v16");
        JsonNode polled = postOk("/api/v1/agents/" + agentId + "/commands/next",
                Map.of("leaseSeconds", 30));

        assertThat(polled.path("id").asText()).isEqualTo(command.path("id").asText());
        assertThat(polled.path("command_type").asText()).isEqualTo("RESET_CLASS");
        JsonNode storedJson = objectMapper.readTree(jdbc.queryForObject(
                "select capabilities_json from agent_instance where id = ?",
                String.class, agentId));
        List<String> storedCapabilities = new ArrayList<>();
        storedJson.forEach(value -> storedCapabilities.add(value.asText()));
        assertThat(storedCapabilities)
                .containsExactlyInAnyOrderElementsOf(v16.capabilities());
    }

    @Test
    void currentV17RegistrationAdvertisesStrictNegotiationAndSucceeds() throws Exception {
        AgentProtocolInfo current = AgentProtocolInfo.currentV17();
        String agentId = register("current", current.protocolVersions(),
                new ArrayList<>(current.capabilities()));

        String stored = jdbc.queryForObject(
                "select capabilities_json from agent_instance where id = ?", String.class, agentId);
        JsonNode capabilities = objectMapper.readTree(stored);
        assertThat(capabilities.isArray()).isTrue();
        assertThat(capabilities.toString()).contains(KairoCommandCapabilities.STRICT_NEGOTIATION);
        assertThat(capabilities.toString()).contains("RESET_CLASS");
    }

    @Test
    void strictAgentMissingCommandCapabilityIsRejectedBeforeEnqueue() throws Exception {
        String agentId = register("strict-missing", List.of("v1"),
                List.of(KairoCommandCapabilities.STRICT_NEGOTIATION, "APPLY_RULE"));

        JsonNode error = postError("/api/v1/agents/" + agentId + "/commands",
                commandRequest("strict-missing"), 409);

        assertThat(error.path("code").asText()).isEqualTo("CAPABILITY_NOT_SUPPORTED");
        assertThat(countCommands(agentId)).isZero();
    }

    @Test
    void capabilityDowngradeAfterEnqueueFailsClosedAndLeavesCommandPending() throws Exception {
        String agentId = register("downgrade", List.of("v1"), List.of(
                KairoCommandCapabilities.STRICT_NEGOTIATION, "RESET_CLASS"));
        JsonNode command = enqueue(agentId, "downgrade");
        jdbc.update("update agent_instance set capabilities_json = ? where id = ?",
                "[\"STRICT_NEGOTIATION\"]", agentId);

        JsonNode error = postError("/api/v1/agents/" + agentId + "/commands/next",
                Map.of("leaseSeconds", 30), 409);

        assertThat(error.path("code").asText()).isEqualTo("CAPABILITY_NOT_SUPPORTED");
        assertPendingUntouched(command.path("id").asText());
    }

    @Test
    void malformedCapabilitiesAfterEnqueueFailClosedAndLeaveCommandPending() throws Exception {
        String agentId = register("malformed", List.of("v1"), List.of(
                KairoCommandCapabilities.STRICT_NEGOTIATION, "RESET_CLASS"));
        JsonNode command = enqueue(agentId, "malformed");
        jdbc.update("update agent_instance set capabilities_json = ? where id = ?",
                "{not-an-array}", agentId);

        JsonNode error = postError("/api/v1/agents/" + agentId + "/commands/next",
                Map.of("leaseSeconds", 30), 409);

        assertThat(error.path("code").asText()).isEqualTo("CAPABILITY_NOT_SUPPORTED");
        assertPendingUntouched(command.path("id").asText());
    }

    @Test
    void unsupportedExplicitProtocolIsRejectedBeforeAnyRegistrationWrite() throws Exception {
        int instancesBefore = count("instance");
        int agentsBefore = count("agent_instance");
        int projectsBefore = count("project");

        JsonNode error = postError("/api/v1/agent-registrations/self",
                registration("v2-only", List.of("v2"), List.of("RESET_CLASS")), 409);

        assertThat(error.path("code").asText()).isEqualTo("PROTOCOL_VERSION_NOT_SUPPORTED");
        assertThat(error.path("category").asText()).isEqualTo("CAPABILITY");
        assertThat(error.path("retryable").asBoolean()).isFalse();
        assertThat(error.at("/details/advertisedProtocolVersions/0").asText()).isEqualTo("v2");
        assertThat(count("instance")).isEqualTo(instancesBefore);
        assertThat(count("agent_instance")).isEqualTo(agentsBefore);
        assertThat(count("project")).isEqualTo(projectsBefore);
    }

    @Test
    void malformedProtocolAdvertisementIsValidationErrorAndWritesNothing() throws Exception {
        int instancesBefore = count("instance");
        int agentsBefore = count("agent_instance");
        int projectsBefore = count("project");
        Map<String, Object> request = registration("malformed-protocol", null,
                List.of("RESET_CLASS"));
        request.put("protocolVersions", List.of("v1", 2));

        JsonNode error = postError("/api/v1/agent-registrations/self", request, 400);

        assertThat(error.path("code").asText()).isEqualTo("INVALID_FIELD");
        assertThat(count("instance")).isEqualTo(instancesBefore);
        assertThat(count("agent_instance")).isEqualTo(agentsBefore);
        assertThat(count("project")).isEqualTo(projectsBefore);
    }

    private String register(String suffix, List<String> protocolVersions,
                            List<String> capabilities) throws Exception {
        JsonNode result = postOk("/api/v1/agent-registrations/self",
                registration(suffix, protocolVersions, capabilities));
        return result.path("agentId").asText();
    }

    private Map<String, Object> registration(String suffix, List<String> protocolVersions,
                                             List<String> capabilities) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("projectName", "protocol-project-" + suffix);
        request.put("applicationName", "protocol-app-" + suffix);
        request.put("hostname", "protocol-host-" + suffix);
        request.put("processId", "17-" + suffix);
        request.put("processStartId", "protocol-start-" + suffix);
        request.put("javaVersion", "21");
        request.put("agentVersion", "1.7.0");
        request.put("capabilities", capabilities);
        if (protocolVersions != null) {
            request.put("protocolVersions", protocolVersions);
        }
        return request;
    }

    private JsonNode enqueue(String agentId, String suffix) throws Exception {
        return postOk("/api/v1/agents/" + agentId + "/commands", commandRequest(suffix));
    }

    private static Map<String, Object> commandRequest(String suffix) {
        return Map.of(
                "commandType", "RESET_CLASS",
                "payload", Map.of("classId", "com.example.Protocol" + suffix),
                "idempotencyKey", "v17-protocol-" + suffix);
    }

    private void assertPendingUntouched(String commandId) {
        Map<String, Object> row = jdbc.queryForMap(
                "select status, attempts, dispatched_at, lease_expires_at from agent_command where id = ?",
                commandId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        assertThat(row.get("dispatched_at")).isNull();
        assertThat(row.get("lease_expires_at")).isNull();
    }

    private int countCommands(String agentId) {
        return jdbc.queryForObject("select count(*) from agent_command where agent_id = ?",
                Integer.class, agentId);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private JsonNode postOk(String path, Object body) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode postError(String path, Object body, int expectedStatus) throws Exception {
        MvcResult result = mockMvc.perform(post(path)
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().is(expectedStatus)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
