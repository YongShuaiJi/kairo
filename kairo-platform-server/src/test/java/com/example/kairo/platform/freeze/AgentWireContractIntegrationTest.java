package com.example.kairo.platform.freeze;

import com.example.kairo.api.protocol.AgentCommandAck;
import com.example.kairo.api.protocol.AgentCommandEnvelope;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.7 M0: proves the shared protocol DTOs against the live Platform controller/service wire.
 * Expected poll properties come from the HTTP response itself and the explicit frozen contract;
 * database DDL is intentionally not used as a wire-schema source.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_v17_agent_wire;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentWireContractIntegrationTest {

    private static final String INSTANCE_ID = "instance-v17-wire";
    private static final String AGENT_ID = "agent-v17-wire";
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "id", "agent_id", "command_type", "status", "idempotency_key", "payload_json",
            "result_json", "attempts", "max_attempts", "available_at", "lease_expires_at",
            "dispatched_at", "completed_at", "error_message", "created_by", "created_at",
            "updated_at", "correlation_id", "rollback_execution_id", "expected_revision",
            "desired_revision", "desired_hash", "result_hash", "payload");

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestPlatformMapper testPlatformMapper;

    @BeforeEach
    void registerAgent() throws Exception {
        testPlatformMapper.ensureDefaultProject();
        testPlatformMapper.ensureDefaultApplication();
        testPlatformMapper.ensureDefaultEnvironment();
        postJson("/api/v1/instances", Map.of(
                "id", INSTANCE_ID,
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "hostname", "v17-wire-host",
                "processId", "17001",
                "runtime", "java-21",
                "labels", Map.of(),
                "reason", "register V1.7 wire fixture"));
        postJson("/api/v1/agents", Map.of(
                "id", AGENT_ID,
                "instanceId", INSTANCE_ID,
                "agentVersion", "1.6.0",
                "bootstrapVersion", "1.6.0",
                "listenHost", "127.0.0.1",
                "listenPort", 18171,
                "tokenHash", "v17-wire-token",
                "capabilities", List.of("JAVA_METHOD", "RESET_CLASS"),
                "reason", "register V1.6-compatible agent"));
    }

    @Test
    void livePollAndAckAreCompatibleWithSharedWireDtos() throws Exception {
        JsonNode historicalV16 = objectMapper.readTree(getClass().getClassLoader()
                .getResourceAsStream("v1.7/fixtures/v1.6-agent-command-wire.json"));
        assertThat(fieldNames(historicalV16))
                .as("actual V1.6.0/113823b HTTP poll capture must witness the complete wire")
                .containsExactlyInAnyOrderElementsOf(ENVELOPE_FIELDS);

        JsonNode first = enqueueAndPoll("wire-acked", "com.example.WireAcked");

        assertThat(fieldNames(first)).containsExactlyInAnyOrderElementsOf(ENVELOPE_FIELDS);
        assertSameWireShape(historicalV16, first, "command envelope");
        assertThat(ENVELOPE_FIELDS).hasSize(24);
        assertThat(dtoJsonProperties()).containsExactlyInAnyOrderElementsOf(ENVELOPE_FIELDS);

        AgentCommandEnvelope envelope = objectMapper.treeToValue(first, AgentCommandEnvelope.class);
        assertThat(envelope.id()).isEqualTo(first.path("id").asText());
        assertThat(envelope.commandType()).isEqualTo("RESET_CLASS");
        assertThat(envelope.payload())
                .containsEntry("classId", "com.example.WireAcked")
                .containsEntry("protocolVersion", "v1");

        JsonNode acked = postJson("/api/v1/agent-commands/" + envelope.id() + "/ack",
                objectMapper.valueToTree(AgentCommandAck.ackedRepresentative()));
        assertThat(acked.path("status").asText()).isEqualTo("ACKED");

        JsonNode second = enqueueAndPoll("wire-failed", "com.example.WireFailed");
        AgentCommandAck failed = new AgentCommandAck(
                "FAILED", null, "java.lang.IllegalStateException: wire failure",
                "agent command failed");
        JsonNode failedResult = postJson("/api/v1/agent-commands/" + second.path("id").asText() + "/ack",
                objectMapper.valueToTree(failed));
        assertThat(failedResult.path("status").asText()).isEqualTo("FAILED");
        assertThat(failedResult.path("error_message").asText())
                .isEqualTo("java.lang.IllegalStateException: wire failure");

        assertThat(fieldNames(objectMapper.valueToTree(AgentCommandAck.ackedRepresentative())))
                .containsExactlyInAnyOrder("status", "result", "reason");
        assertThat(fieldNames(objectMapper.valueToTree(AgentCommandAck.failedRepresentative())))
                .containsExactlyInAnyOrder("status", "errorMessage", "reason");
        assertThat(fieldNames(objectMapper.valueToTree(
                AgentCommandAck.capabilityFailureRepresentative())))
                .containsExactlyInAnyOrder("status", "result", "errorMessage", "reason");
    }

    private JsonNode enqueueAndPoll(String suffix, String classId) throws Exception {
        postJson("/api/v1/agents/" + AGENT_ID + "/commands", Map.of(
                "commandType", "RESET_CLASS",
                "payload", Map.of("classId", classId),
                "idempotencyKey", "v17-wire-" + suffix,
                "reason", "freeze agent wire"));
        return postJson("/api/v1/agents/" + AGENT_ID + "/commands/next",
                Map.of("leaseSeconds", 60));
    }

    private JsonNode postJson(String path, Object body) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new LinkedHashSet<>();
        Iterator<String> iterator = node.fieldNames();
        iterator.forEachRemaining(names::add);
        return names;
    }

    private static void assertSameWireShape(JsonNode historical, JsonNode current, String path) {
        if (historical.isObject()) {
            assertThat(current.isObject()).as(path + " object type").isTrue();
            assertThat(fieldNames(current)).as(path + " object fields")
                    .containsExactlyInAnyOrderElementsOf(fieldNames(historical));
            historical.fields().forEachRemaining(entry ->
                    assertSameWireShape(entry.getValue(), current.path(entry.getKey()),
                            path + "." + entry.getKey()));
            return;
        }
        if (historical.isArray()) {
            assertThat(current.isArray()).as(path + " array type").isTrue();
            return;
        }
        if (historical.isNull()) {
            assertThat(current.isNull()).as(path + " nullability at captured lifecycle state").isTrue();
            return;
        }
        if (historical.isNumber()) {
            assertThat(current.isNumber()).as(path + " numeric type").isTrue();
            return;
        }
        if (historical.isBoolean()) {
            assertThat(current.isBoolean()).as(path + " boolean type").isTrue();
            return;
        }
        assertThat(current.isTextual()).as(path + " string type").isTrue();
    }

    private static Set<String> dtoJsonProperties() {
        Set<String> names = new LinkedHashSet<>();
        for (RecordComponent component : AgentCommandEnvelope.class.getRecordComponents()) {
            JsonProperty property = component.getAccessor().getAnnotation(JsonProperty.class);
            names.add(property == null ? component.getName() : property.value());
        }
        return names;
    }
}
