package com.example.runtimemock.platform;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformControllerIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void unloadsPublishedRuleAndCompletesOnlyAfterAgentAck() throws Exception {
        String instanceId = "instance-unload-1";
        String agentId = "agent-unload-1";
        String ruleId = "rule-unload-1";
        String operationId = "operation-unload-1";

        postJson("/api/v1/instances", Map.of(
                "id", instanceId,
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "hostname", "unload-host",
                "processId", "9001",
                "runtime", "java-21",
                "labels", Map.of("purpose", "unload-test"),
                "reason", "register unload test instance"
        ), "system");
        postJson("/api/v1/agents", Map.of(
                "id", agentId,
                "instanceId", instanceId,
                "agentVersion", "0.1.0",
                "bootstrapVersion", "0.1.0",
                "listenHost", "127.0.0.1",
                "listenPort", 18091,
                "tokenHash", "unload-token",
                "capabilities", java.util.List.of("JAVA_METHOD", "RESET_CLASS"),
                "reason", "register unload test agent"
        ), "system");
        postJson("/api/v1/rules", Map.ofEntries(
                Map.entry("id", ruleId),
                Map.entry("applicationId", "app-default"),
                Map.entry("environmentId", "env-dev"),
                Map.entry("name", "Unload integration rule"),
                Map.entry("riskLevel", "LOW"),
                Map.entry("matcher", Map.of()),
                Map.entry("script", Map.of("type", "RETURN", "value", "mocked")),
                Map.entry("governance", Map.of()),
                Map.entry("targets", java.util.List.of(Map.of(
                        "protocol", "JAVA_METHOD",
                        "className", "com.example.UnloadService",
                        "methodName", "query",
                        "matcher", Map.of("classId", "com.example.UnloadService")
                ))),
                Map.entry("capabilities", java.util.List.of("EARLY_RETURN")),
                Map.entry("reason", "create unload test rule")
        ), "system");
        postJson("/api/v1/operation-plans", Map.of(
                "id", operationId,
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "planType", "RULE_ROLLOUT",
                "resourceType", "rule",
                "resourceId", ruleId,
                "resourceVersion", 1,
                "strategy", Map.of(
                        "targetMode", "ALL_ACTIVE_INSTANCES",
                        "automaticUnload", true
                ),
                "reason", "create unload test operation"
        ), "system");
        jdbcTemplate.update("""
                insert into rollout_instance_execution(
                    id, rollout_batch_id, operation_plan_id, instance_id, status,
                    expected_agent_version, expected_rule_version, command_id, error_message,
                    started_at, finished_at, version, updated_by, updated_at
                ) values (?, null, ?, ?, 'SUCCEEDED', '0.1.0', 1, null, null,
                          current_timestamp, current_timestamp, 1, 'system', current_timestamp)
                """, "rollout-execution-unload-1", operationId, instanceId);
        jdbcTemplate.update("""
                update operation_plan set status = 'SUCCEEDED', version = 2 where id = ?
                """, operationId);
        jdbcTemplate.update("""
                insert into rule_runtime_status(
                    id, rule_id, rule_version, instance_id, status,
                    hit_count, error_count, last_error, updated_at
                ) values (?, ?, 1, ?, 'ACTIVE', 0, 0, null, current_timestamp)
                """, "runtime-status-unload-1", ruleId, instanceId);

        String fencingToken = issueFencingToken("operation_plan", operationId, "unload rule");
        JsonNode unload = postJson("/api/v1/operation-plans/" + operationId + "/unload", Map.of(
                "expectedStatus", "SUCCEEDED",
                "expectedVersion", 2,
                "fencingToken", fencingToken,
                "reason", "integration test unload"
        ), "system");
        assertThat(unload.at("/operationPlan/status").asText()).isEqualTo("UNLOADING");
        assertThat(unload.get("commandCount").asInt()).isEqualTo(1);

        JsonNode command = postJson("/api/v1/agents/" + agentId + "/commands/next",
                Map.of("leaseSeconds", 60), "system");
        assertThat(command.get("command_type").asText()).isEqualTo("RESET_CLASS");
        assertThat(command.at("/payload/classId").asText()).isEqualTo("com.example.UnloadService");

        postJson("/api/v1/agent-commands/" + command.get("id").asText() + "/ack", Map.of(
                "status", "ACKED",
                "result", Map.of("removedRuleIds", java.util.List.of(ruleId + ":1")),
                "reason", "class reset completed"
        ), "system");

        assertThat(getJson("/api/v1/details/operation-plans/" + operationId)
                .get("status").asText()).isEqualTo("UNLOADED");
        assertThat(jdbcTemplate.queryForObject(
                "select status from rule_runtime_status where id = ?",
                String.class, "runtime-status-unload-1")).isEqualTo("REMOVED");
    }

    @Test
    void persistsCoreControlPlaneResourcesAndRunsRuleOperation() throws Exception {
        JsonNode health = getJson("/api/v1/control/health");
        assertThat(health.get("status").asText()).isEqualTo("UP");

        JsonNode instance = postJson("/api/v1/instances", Map.of(
                "id", "instance-platform-1",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "hostname", "localhost",
                "processId", "12345",
                "runtime", "java-21",
                "labels", Map.of("az", "local", "tier", "demo"),
                "reason", "register instance"
        ), "system");
        assertThat(instance.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode agent = postJson("/api/v1/agents", Map.of(
                "id", "agent-platform-1",
                "instanceId", "instance-platform-1",
                "agentVersion", "0.1.0",
                "bootstrapVersion", "0.1.0",
                "listenHost", "127.0.0.1",
                "listenPort", 18081,
                "tokenHash", "sha256-token",
                "capabilities", java.util.List.of("JAVA_METHOD", "RESET_CLASS"),
                "reason", "register agent"
        ), "system");
        assertThat(agent.get("listen_port").asInt()).isEqualTo(18081);

        JsonNode heartbeat = postJson("/api/v1/agents/agent-platform-1/heartbeat", Map.of(
                "status", "ACTIVE",
                "metrics", Map.of("queueDepth", 0),
                "reason", "heartbeat"
        ), "system");
        assertThat(heartbeat.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode rule = postJson("/api/v1/rules", Map.ofEntries(
                Map.entry("id", "rule-platform-1"),
                Map.entry("applicationId", "app-default"),
                Map.entry("environmentId", "env-dev"),
                Map.entry("name", "Demo method latency fault"),
                Map.entry("riskLevel", "MEDIUM"),
                Map.entry("matcher", Map.of("stableSamplingKey", "traceId")),
                Map.entry("script", Map.of("type", "RETURN", "value", "mocked")),
                Map.entry("governance", Map.of("ttlSeconds", 600, "maxHits", 100)),
                Map.entry("targets", java.util.List.of(Map.of(
                        "protocol", "JAVA_METHOD",
                        "className", "com.example.DemoService",
                        "methodName", "query",
                        "matcher", Map.of("descriptor", "(Ljava/lang/String;)Ljava/lang/String;")
                ))),
                Map.entry("capabilities", java.util.List.of("EARLY_RETURN")),
                Map.entry("reason", "create rule")
        ), "system");
        assertThat(rule.get("latest_version").asLong()).isEqualTo(1);

        JsonNode ruleVersion = postJson("/api/v1/rules/rule-platform-1/versions", Map.of(
                "riskLevel", "HIGH",
                "versionStatus", "DRAFT",
                "matcher", Map.of("stableSamplingKey", "userId"),
                "script", Map.of("type", "THROW", "exception", "java.lang.IllegalStateException"),
                "governance", Map.of("ttlSeconds", 300, "maxHits", 10),
                "targets", java.util.List.of(Map.of(
                        "protocol", "JAVA_METHOD",
                        "className", "com.example.DemoService",
                        "methodName", "query"
                )),
                "capabilities", java.util.List.of("THROW_EXCEPTION"),
                "reason", "tighten rule"
        ), "system");
        assertThat(ruleVersion.get("version").asLong()).isEqualTo(2);

        JsonNode operation = postJson("/api/v1/operation-plans", Map.of(
                "id", "operation-platform-1",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "planType", "RULE_ROLLOUT",
                "resourceType", "rule",
                "resourceId", "rule-platform-1",
                "resourceVersion", 2,
                "strategy", Map.of(
                        "targetMode", "ALL_ACTIVE_INSTANCES",
                        "automaticUnload", true
                ),
                "reason", "create operation"
        ), "system");
        assertThat(operation.get("status").asText()).isEqualTo("DRAFT");
        JsonNode runningOperation = transitionOperation("operation-platform-1", "DRAFT", 1, "RUNNING");
        assertThat(runningOperation.get("version").asLong()).isEqualTo(2);

        assertThat(jdbcTemplate.queryForObject(
                "select status from rule_version where rule_id = ? and version = ?",
                String.class, "rule-platform-1", 2)).isEqualTo("APPROVED");
        assertThat(getJson("/api/v1/control/health").get("operationPlanCount").asLong()).isGreaterThanOrEqualTo(1);
    }

    private JsonNode transitionOperation(String id, String expectedStatus, long expectedVersion,
                                         String targetStatus) throws Exception {
        String token = issueFencingToken("operation_plan", id, "move to " + targetStatus);
        return postJson("/api/v1/operation-plans/" + id + "/transition", Map.of(
                "expectedStatus", expectedStatus,
                "expectedVersion", expectedVersion,
                "targetStatus", targetStatus,
                "reason", "move to " + targetStatus,
                "fencingToken", token
        ), "system");
    }

    private String issueFencingToken(String resourceType, String resourceId, String purpose) throws Exception {
        JsonNode token = postJson("/api/v1/fencing-tokens", Map.of(
                "resourceType", resourceType,
                "resourceId", resourceId,
                "purpose", purpose,
                "ttlSeconds", 300,
                "reason", purpose
        ), "system");
        assertThat(token.get("token").asText()).contains(resourceType + ":" + resourceId + ":");
        return token.get("token").asText();
    }

    private JsonNode getJson(String path) throws Exception {
        String body = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode postJson(String path, Object request, String actor) throws Exception {
        String body = mockMvc.perform(post(path)
                        .header("X-Actor", actor)
                        .header("X-Correlation-Id", "corr-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }

    private void postJsonExpectingStatus(String path, Object request, String actor, int expectedStatus)
            throws Exception {
        mockMvc.perform(post(path)
                        .header("X-Actor", actor)
                        .header("X-Correlation-Id", "corr-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().is(expectedStatus));
    }
}
