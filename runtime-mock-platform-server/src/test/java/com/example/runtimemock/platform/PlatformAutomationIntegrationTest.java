package com.example.runtimemock.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:runtime_mock_platform_auto;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "runtime-mock.platform.rollout.scheduler.enabled=true",
        "runtime-mock.platform.rollout.scheduler.fixed-delay-ms=600000",
        "runtime-mock.platform.extraction.worker.enabled=true",
        "runtime-mock.platform.extraction.worker.fixed-delay-ms=600000",
        "runtime-mock.platform.replay.worker.enabled=true",
        "runtime-mock.platform.replay.worker.fixed-delay-ms=600000",
        "runtime-mock.platform.object-store.local-root=target/platform-test-objects"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformAutomationIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void runsRolloutExtractionAndReplayWorkersEndToEnd() throws Exception {
        createRolloutFixture();
        JsonNode rolloutRun = postJson("/api/v1/control/schedulers/run-once", Map.of(), "system");
        assertThat(rolloutRun.path("rollout").path("commandsEnqueued").asInt()).isEqualTo(1);

        JsonNode command = postJsonAs("/api/v1/agents/agent-auto/commands/next",
                Map.of("leaseSeconds", 30), "agent-auto", "agent");
        assertThat(command.get("status").asText()).isEqualTo("DISPATCHED");
        postJsonAs("/api/v1/agent-commands/" + command.get("id").asText() + "/ack",
                Map.of("status", "ACKED", "result", Map.of("applied", true), "reason", "applied"),
                "agent-auto", "agent");
        assertThat(findById(getJson("/api/v1/operation-plans"), "operation-auto").get("status").asText())
                .isEqualTo("SUCCEEDED");
        assertThat(findById(getJson("/api/v1/agent-commands"), command.get("id").asText()).get("status").asText())
                .isEqualTo("ACKED");

        createExtractionFixture();
        JsonNode extractionRun = postJson("/api/v1/control/schedulers/run-once", Map.of(), "system");
        assertThat(extractionRun.path("extraction").path("succeeded").asInt()).isEqualTo(1);
        assertThat(findById(getJson("/api/v1/extraction-tasks"), "extraction-task-auto").get("status").asText())
                .isEqualTo("SUCCEEDED");
        assertThat(getJson("/api/v1/extraction-results")).hasSizeGreaterThanOrEqualTo(1);
        assertThat(getJson("/api/v1/worker-artifacts")).hasSizeGreaterThanOrEqualTo(1);

        createReplayFixture();
        JsonNode replayRun = postJson("/api/v1/control/schedulers/run-once", Map.of(), "system");
        assertThat(replayRun.path("replay").path("succeeded").asInt()).isEqualTo(1);
        assertThat(findById(getJson("/api/v1/replay-executions"), "replay-execution-auto").get("status").asText())
                .isEqualTo("SUCCEEDED");
        assertThat(getJson("/api/v1/replay-batches")).hasSizeGreaterThanOrEqualTo(1);
        assertThat(getJson("/api/v1/replay-invocation-results")).hasSizeGreaterThanOrEqualTo(1);
        assertThat(getJson("/api/v1/comparison-results")).hasSizeGreaterThanOrEqualTo(1);
    }

    private void createRolloutFixture() throws Exception {
        postJson("/api/v1/instances", Map.of(
                "id", "instance-auto",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "hostname", "auto-host",
                "processId", "auto",
                "runtime", "java-21",
                "labels", Map.of("tier", "auto"),
                "reason", "automation test"
        ), "system");
        postJson("/api/v1/agents", Map.of(
                "id", "agent-auto",
                "instanceId", "instance-auto",
                "status", "ACTIVE",
                "agentVersion", "0.1.0",
                "bootstrapVersion", "0.1.0",
                "listenHost", "127.0.0.1",
                "listenPort", 18090,
                "tokenHash", "sha256-token",
                "capabilities", java.util.List.of("JAVA_METHOD"),
                "reason", "automation test"
        ), "system");
        postJson("/api/v1/rules", Map.ofEntries(
                Map.entry("id", "rule-auto"),
                Map.entry("applicationId", "app-default"),
                Map.entry("environmentId", "env-dev"),
                Map.entry("name", "Auto rollout rule"),
                Map.entry("versionStatus", "ACTIVE"),
                Map.entry("riskLevel", "LOW"),
                Map.entry("script", Map.of("phase", "BEFORE", "script", "return mock.proceed(args)")),
                Map.entry("targets", java.util.List.of(Map.of(
                        "protocol", "JAVA_METHOD",
                        "className", "com.example.AutoService",
                        "methodName", "call",
                        "matcher", Map.of("descriptor", "()Ljava/lang/String;")
                ))),
                Map.entry("reason", "automation test")
        ), "system");
        postJson("/api/v1/operation-plans", Map.of(
                "id", "operation-auto",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "planType", "RULE_ROLLOUT",
                "resourceType", "rule",
                "resourceId", "rule-auto",
                "resourceVersion", 1,
                "strategy", Map.of("mode", "canary"),
                "rollout", Map.of("mode", "SEQUENTIAL", "batchPolicy", Map.of("batchSize", 1)),
                "reason", "automation test"
        ), "system");
        transitionOperation("operation-auto", "DRAFT", 1, "WAITING_APPROVAL");
        approveSubject("approval-operation-auto", "OPERATION_PLAN", "operation-auto", 2);
        transitionOperation("operation-auto", "WAITING_APPROVAL", 2, "APPROVED");
        transitionOperation("operation-auto", "APPROVED", 3, "RUNNING");
        postJson("/api/v1/operation-plans/operation-auto/batches", Map.of(
                "id", "rollout-batch-auto",
                "batchOrder", 1,
                "targetSelector", Map.of("labels", Map.of("tier", "auto")),
                "reason", "automation test"
        ), "system");
    }

    private void createExtractionFixture() throws Exception {
        postJson("/api/v1/datasources", Map.of(
                "id", "datasource-auto",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "datasourceType", "TEST_FIXTURE",
                "name", "sample datasource",
                "config", Map.of("sampleRows", java.util.List.of(
                        Map.of("id", "order-1", "status", "PAID"),
                        Map.of("id", "order-2", "status", "CREATED")
                )),
                "reason", "automation test"
        ), "system");
        postJson("/api/v1/extraction-templates", Map.of(
                "id", "extraction-template-auto",
                "datasourceId", "datasource-auto",
                "name", "sample extraction",
                "rootTable", "orders",
                "template", Map.of("columns", java.util.List.of("id", "status")),
                "quota", Map.of("maxRows", 10, "timeoutSeconds", 5),
                "reason", "automation test"
        ), "system");
        postJson("/api/v1/extraction-tasks", Map.of(
                "id", "extraction-task-auto",
                "templateId", "extraction-template-auto",
                "templateVersion", 1,
                "datasetId", "dataset-auto-extract",
                "parameters", Map.of(),
                "quota", Map.of("maxRows", 10, "timeoutSeconds", 5),
                "reason", "automation test"
        ), "system");
        transitionExtraction("extraction-task-auto", "DRAFT", 1, "QUEUED");
    }

    private void createReplayFixture() throws Exception {
        postJson("/api/v1/recording-sessions", Map.of(
                "id", "rec-auto",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "maxEvents", 10,
                "ttlSeconds", 600,
                "target", Map.of("protocol", "JAVA_METHOD"),
                "reason", "automation test"
        ), "system");
        transitionRecording("rec-auto", "DRAFT", 1, "WAITING_APPROVAL");
        approveSubject("approval-rec-auto", "RECORDING_SESSION", "rec-auto", 2);
        transitionRecording("rec-auto", "WAITING_APPROVAL", 2, "APPROVED");
        transitionRecording("rec-auto", "APPROVED", 3, "RECORDING");
        transitionRecording("rec-auto", "RECORDING", 4, "COMPLETED");
        postJson("/api/v1/datasets", Map.of(
                "datasetId", "dataset-auto",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "sourceSessionId", "rec-auto",
                "schemaHash", "schema-auto",
                "manifestHash", "manifest-auto",
                "maskingHash", "masking-auto",
                "retentionPolicy", "P30D",
                "reason", "automation test"
        ), "system");
        postJson("/api/v1/replay-plans", Map.of(
                "id", "replay-auto",
                "datasetId", "dataset-auto",
                "datasetVersion", 1,
                "targetEnvironment", "test",
                "targetApplication", "auto-service",
                "sideEffectPolicyHash", "side-effect-auto",
                "comparisonPolicyHash", "comparison-auto",
                "executionPolicy", Map.of("qps", 1),
                "targets", java.util.List.of(Map.of(
                        "targetType", "SYNTHETIC",
                        "name", "synthetic target"
                )),
                "reason", "automation test"
        ), "system");
        postJson("/api/v1/replay-executions", Map.of(
                "id", "replay-execution-auto",
                "replayPlanId", "replay-auto",
                "executorConfig", Map.of("qps", 1),
                "reason", "automation test"
        ), "system");
    }

    private void transitionOperation(String id, String expectedStatus, long expectedVersion,
                                     String targetStatus) throws Exception {
        String token = issueFencingToken("operation_plan", id, "move to " + targetStatus);
        postJson("/api/v1/operation-plans/" + id + "/transition", Map.of(
                "expectedStatus", expectedStatus,
                "expectedVersion", expectedVersion,
                "targetStatus", targetStatus,
                "reason", "move to " + targetStatus,
                "fencingToken", token
        ), "system");
    }

    private void approveSubject(String approvalId, String subjectType, String subjectId, long subjectVersion)
            throws Exception {
        postJsonAs("/api/v1/approvals", Map.of(
                "id", approvalId,
                "subjectType", subjectType,
                "subjectId", subjectId,
                "subjectVersion", subjectVersion,
                "reason", "automation approval",
                "approvers", java.util.List.of("reviewer")
        ), "system", "header-dev");
        postJsonAs("/api/v1/approvals/" + approvalId + "/decisions", Map.of(
                "decision", "APPROVED",
                "reason", "automation approved"
        ), "reviewer", "header-dev");
    }

    private void transitionExtraction(String id, String expectedStatus, long expectedVersion,
                                      String targetStatus) throws Exception {
        String token = issueFencingToken("extraction_task", id, "move to " + targetStatus);
        postJson("/api/v1/extraction-tasks/" + id + "/transition", Map.of(
                "expectedStatus", expectedStatus,
                "expectedVersion", expectedVersion,
                "targetStatus", targetStatus,
                "reason", "move to " + targetStatus,
                "fencingToken", token
        ), "system");
    }

    private void transitionRecording(String id, String expectedStatus, long expectedVersion,
                                     String targetStatus) throws Exception {
        String token = issueFencingToken("recording_session", id, "move to " + targetStatus);
        postJson("/api/v1/recording-sessions/" + id + "/transition", Map.of(
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
        return token.get("token").asText();
    }

    private JsonNode findById(JsonNode array, String id) {
        for (JsonNode item : array) {
            if (id.equals(item.get("id").asText())) {
                return item;
            }
        }
        throw new AssertionError("Cannot find id " + id + " in " + array);
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
        return postJsonAs(path, request, actor, "header-dev");
    }

    private JsonNode postJsonAs(String path, Object request, String actor, String identitySource) throws Exception {
        String body = mockMvc.perform(post(path)
                        .header("X-Actor", actor)
                        .header("X-Identity-Source", identitySource)
                        .header("X-Correlation-Id", "corr-auto-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(body);
    }
}
