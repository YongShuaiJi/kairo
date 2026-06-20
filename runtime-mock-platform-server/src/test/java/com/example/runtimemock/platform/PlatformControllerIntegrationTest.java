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
                "strategy", Map.of("mode", "canary"),
                "rollout", Map.of("mode", "SEQUENTIAL"),
                "reason", "create unload test operation"
        ), "system");
        String batchId = jdbcTemplate.queryForObject(
                "select id from rollout_batch where operation_plan_id = ?", String.class, operationId);
        postJson("/api/v1/rollout-batches/" + batchId + "/executions", Map.of(
                "id", "rollout-execution-unload-1",
                "instanceId", instanceId,
                "expectedAgentVersion", "0.1.0",
                "expectedRuleVersion", 1,
                "reason", "create unload execution"
        ), "system");
        jdbcTemplate.update("""
                update operation_plan set status = 'SUCCEEDED', version = 2 where id = ?
                """, operationId);
        jdbcTemplate.update("""
                update rollout_instance_execution set status = 'SUCCEEDED' where id = ?
                """, "rollout-execution-unload-1");
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
        assertThat(unload.at("/operationPlan/status").asText()).isEqualTo("ROLLING_BACK");
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
                .get("status").asText()).isEqualTo("ROLLED_BACK");
        assertThat(jdbcTemplate.queryForObject(
                "select status from rule_runtime_status where id = ?",
                String.class, "runtime-status-unload-1")).isEqualTo("REMOVED");
    }

    @Test
    void persistsRecordingDatasetReplayApprovalAuditAndOutbox() throws Exception {
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

        JsonNode sidecar = postJson("/api/v1/sidecars", Map.of(
                "id", "sidecar-platform-1",
                "instanceId", "instance-platform-1",
                "sidecarVersion", "0.1.0",
                "endpoint", "https://127.0.0.1:18443",
                "capabilities", java.util.List.of("MTLS", "WAL"),
                "reason", "register sidecar"
        ), "system");
        assertThat(sidecar.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode agent = postJson("/api/v1/agents", Map.of(
                "id", "agent-platform-1",
                "instanceId", "instance-platform-1",
                "sidecarId", "sidecar-platform-1",
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
                "versionStatus", "ACTIVE",
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

        JsonNode recordingRule = postJson("/api/v1/recording-rules", Map.of(
                "id", "recording-rule-platform-1",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "name", "Record demo method",
                "protocol", "JAVA_METHOD",
                "target", Map.of("className", "com.example.DemoService", "methodName", "query"),
                "sampling", Map.of("rate", 0.1),
                "quota", Map.of("maxEvents", 100, "maxBytes", 1024),
                "reason", "create recording rule"
        ), "system");
        assertThat(recordingRule.get("latest_version").asLong()).isEqualTo(1);

        JsonNode recordingRuleVersion = postJson("/api/v1/recording-rules/recording-rule-platform-1/versions", Map.of(
                "protocol", "JAVA_METHOD",
                "target", Map.of("className", "com.example.DemoService", "methodName", "queryV2"),
                "sampling", Map.of("rate", 0.05),
                "quota", Map.of("maxEvents", 50, "maxBytes", 2048),
                "reason", "create recording rule version"
        ), "system");
        assertThat(recordingRuleVersion.get("version").asLong()).isEqualTo(2);

        JsonNode operation = postJson("/api/v1/operation-plans", Map.of(
                "id", "operation-platform-1",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "planType", "RULE_ROLLOUT",
                "resourceType", "rule",
                "resourceId", "rule-platform-1",
                "resourceVersion", 2,
                "strategy", Map.of("mode", "canary", "observeSeconds", 60),
                "rollout", Map.of(
                        "mode", "SEQUENTIAL",
                        "batchPolicy", Map.of("batchSize", 1),
                        "rollbackPolicy", Map.of("automatic", true)
                ),
                "reason", "create operation"
        ), "system");
        assertThat(operation.get("status").asText()).isEqualTo("DRAFT");
        transitionOperation("operation-platform-1", "DRAFT", 1, "WAITING_APPROVAL");
        approveSubject("approval-operation-platform-1", "OPERATION_PLAN", "operation-platform-1", 2);
        transitionOperation("operation-platform-1", "WAITING_APPROVAL", 2, "APPROVED");
        transitionOperation("operation-platform-1", "APPROVED", 3, "SCHEDULED");
        JsonNode runningOperation = transitionOperation("operation-platform-1", "SCHEDULED", 4, "RUNNING");
        assertThat(runningOperation.get("version").asLong()).isEqualTo(5);

        JsonNode batch = postJson("/api/v1/operation-plans/operation-platform-1/batches", Map.of(
                "id", "rollout-batch-platform-1",
                "batchOrder", 2,
                "targetSelector", Map.of("labels", Map.of("tier", "demo")),
                "reason", "create batch"
        ), "system");
        assertThat(batch.get("batch_order").asInt()).isEqualTo(2);

        JsonNode execution = postJson("/api/v1/rollout-batches/rollout-batch-platform-1/executions", Map.of(
                "id", "rollout-execution-platform-1",
                "instanceId", "instance-platform-1",
                "expectedAgentVersion", "0.1.0",
                "expectedRuleVersion", 2,
                "reason", "create execution"
        ), "system");
        assertThat(execution.get("status").asText()).isEqualTo("PENDING");

        JsonNode recording = postJson("/api/v1/recording-sessions", Map.of(
                "id", "rec-platform-1",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "maxEvents", 100,
                "ttlSeconds", 600,
                "target", Map.of("protocol", "JAVA_METHOD"),
                "quota", Map.of("maxBytes", 1024),
                "reason", "integration test"
        ), "system");
        assertThat(recording.get("status").asText()).isEqualTo("DRAFT");

        transitionRecording("rec-platform-1", "DRAFT", 1, "WAITING_APPROVAL");
        approveSubject("approval-rec-platform-1", "RECORDING_SESSION", "rec-platform-1", 2);
        transitionRecording("rec-platform-1", "WAITING_APPROVAL", 2, "APPROVED");
        transitionRecording("rec-platform-1", "APPROVED", 3, "RECORDING");
        JsonNode completed = transitionRecording("rec-platform-1", "RECORDING", 4, "COMPLETED");
        assertThat(completed.get("version").asLong()).isEqualTo(5);

        JsonNode dataset = postJson("/api/v1/datasets", Map.of(
                "datasetId", "dataset-platform",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "sourceSessionId", "rec-platform-1",
                "schemaHash", "schema-sha256",
                "manifestHash", "manifest-sha256",
                "maskingHash", "masking-sha256",
                "retentionPolicy", "P30D",
                "reason", "build dataset"
        ), "system");
        assertThat(dataset.get("id").asText()).isEqualTo("dataset-platform:1");

        JsonNode datasource = postJson("/api/v1/datasources", Map.of(
                "id", "datasource-platform-1",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "datasourceType", "POSTGRESQL",
                "name", "orders readonly",
                "config", Map.of("host", "orders-db", "database", "orders"),
                "credential", Map.of("provider", "VAULT", "secretRef", "secret/data/orders/readonly"),
                "reason", "register datasource"
        ), "system");
        assertThat(datasource.get("status").asText()).isEqualTo("ACTIVE");

        JsonNode extractionTemplate = postJson("/api/v1/extraction-templates", Map.of(
                "id", "extraction-template-platform-1",
                "datasourceId", "datasource-platform-1",
                "name", "order by id",
                "rootTable", "orders",
                "template", Map.of("where", "id = :id", "columns", java.util.List.of("id", "status")),
                "quota", Map.of("maxRows", 100, "timeoutSeconds", 5),
                "relations", java.util.List.of(Map.of(
                        "sourceTable", "orders",
                        "targetTable", "order_items",
                        "relation", Map.of("sourceColumn", "id", "targetColumn", "order_id")
                )),
                "reason", "create extraction template"
        ), "system");
        assertThat(extractionTemplate.get("latest_version").asLong()).isEqualTo(1);

        JsonNode extractionTask = postJson("/api/v1/extraction-tasks", Map.of(
                "id", "extraction-task-platform-1",
                "templateId", "extraction-template-platform-1",
                "templateVersion", 1,
                "datasetId", "dataset-platform",
                "parameters", Map.of("id", "order-1"),
                "quota", Map.of("maxRows", 10, "timeoutSeconds", 5),
                "reason", "create extraction task"
        ), "system");
        assertThat(extractionTask.get("status").asText()).isEqualTo("DRAFT");
        transitionExtraction("extraction-task-platform-1", "DRAFT", 1, "QUEUED");
        transitionExtraction("extraction-task-platform-1", "QUEUED", 2, "RUNNING");
        JsonNode completedExtraction = transitionExtraction("extraction-task-platform-1", "RUNNING", 3, "SUCCEEDED");
        assertThat(completedExtraction.get("version").asLong()).isEqualTo(4);

        JsonNode replay = postJson("/api/v1/replay-plans", Map.of(
                "id", "replay-platform-1",
                "datasetId", "dataset-platform",
                "datasetVersion", 1,
                "targetEnvironment", "test",
                "targetApplication", "order-service",
                "sideEffectPolicyHash", "side-effect-sha256",
                "comparisonPolicyHash", "comparison-sha256",
                "executionPolicy", Map.of("qps", 10),
                "reason", "create replay"
        ), "system");
        assertThat(replay.get("status").asText()).isEqualTo("DRAFT");

        JsonNode replayExecution = postJson("/api/v1/replay-executions", Map.of(
                "id", "replay-execution-platform-1",
                "replayPlanId", "replay-platform-1",
                "executorConfig", Map.of("qps", 1, "concurrency", 1),
                "reason", "create replay execution"
        ), "system");
        assertThat(replayExecution.get("status").asText()).isEqualTo("QUEUED");
        transitionReplayExecution("replay-execution-platform-1", "QUEUED", 1, "RUNNING");
        JsonNode completedReplayExecution = transitionReplayExecution(
                "replay-execution-platform-1", "RUNNING", 2, "SUCCEEDED");
        assertThat(completedReplayExecution.get("version").asLong()).isEqualTo(3);

        JsonNode approval = postJson("/api/v1/approvals", Map.of(
                "id", "approval-platform-1",
                "subjectType", "REPLAY_PLAN",
                "subjectId", "replay-platform-1",
                "subjectVersion", 1,
                "reason", "approve replay",
                "approvers", java.util.List.of("reviewer")
        ), "system");
        assertThat(approval.get("status").asText()).isEqualTo("WAITING_APPROVAL");

        JsonNode decided = postJson("/api/v1/approvals/approval-platform-1/decisions", Map.of(
                "decision", "APPROVED",
                "reason", "looks safe"
        ), "reviewer");
        assertThat(decided.get("status").asText()).isEqualTo("APPROVED");

        JsonNode audits = getJson("/api/v1/audits");
        JsonNode outbox = getJson("/api/v1/outbox");
        assertThat(audits).hasSizeGreaterThanOrEqualTo(8);
        assertThat(outbox).hasSizeGreaterThanOrEqualTo(8);
        assertThat(audits.get(0).get("previous_record_hash").asText()).isEqualTo("GENESIS");
        assertThat(audits.get(1).get("previous_record_hash").asText())
                .isEqualTo(audits.get(0).get("record_hash").asText());
    }

    @Test
    void enforcesSafeRecordingAndApprovalDefaults() throws Exception {
        JsonNode recording = postJson("/api/v1/recording-sessions", Map.of(
                "id", "rec-safe-defaults",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "target", Map.of("protocol", "JAVA_METHOD"),
                "reason", "verify safe defaults"
        ), "system");
        assertThat(recording.get("ttl_seconds").asLong()).isEqualTo(900);

        postJson("/api/v1/recording-rules", Map.of(
                "id", "recording-rule-safe-defaults",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "name", "Safe default sampling",
                "protocol", "JAVA_METHOD",
                "target", Map.of("className", "com.example.DemoService", "methodName", "query"),
                "reason", "verify safe defaults"
        ), "system");
        JsonNode recordingRuleVersions = getJson("/api/v1/recording-rule-versions");
        JsonNode safeVersion = null;
        for (JsonNode version : recordingRuleVersions) {
            if ("recording-rule-safe-defaults".equals(version.get("recording_rule_id").asText())) {
                safeVersion = version;
                break;
            }
        }
        assertThat(safeVersion).isNotNull();
        assertThat(objectMapper.readTree(safeVersion.get("sampling_json").asText()).get("rate").asDouble())
                .isEqualTo(0.001);

        postJsonExpectingStatus("/api/v1/recording-sessions", Map.of(
                "id", "rec-invalid-ttl",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "ttlSeconds", 7_201,
                "reason", "reject invalid ttl"
        ), "system", 400);

        JsonNode defaultSelfApproval = postJson("/api/v1/approvals", Map.of(
                "id", "approval-without-approver",
                "subjectType", "RECORDING_SESSION",
                "subjectId", "rec-safe-defaults",
                "subjectVersion", 1,
                "reason", "default requester as approver"
        ), "system");
        assertThat(defaultSelfApproval.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        JsonNode defaultSelfApproved = postJson("/api/v1/approvals/approval-without-approver/decisions", Map.of(
                "decision", "APPROVED",
                "reason", "approve own request by default"
        ), "system");
        assertThat(defaultSelfApproved.get("status").asText()).isEqualTo("APPROVED");

        JsonNode selfApproval = postJson("/api/v1/approvals", Map.of(
                "id", "approval-self-approver",
                "subjectType", "RECORDING_SESSION",
                "subjectId", "rec-safe-defaults",
                "subjectVersion", 1,
                "approvers", java.util.List.of("system"),
                "reason", "allow self approver"
        ), "system");
        assertThat(selfApproval.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        JsonNode selfApproved = postJson("/api/v1/approvals/approval-self-approver/decisions", Map.of(
                "decision", "APPROVED",
                "reason", "approve own request"
        ), "system");
        assertThat(selfApproved.get("status").asText()).isEqualTo("APPROVED");

        postJson("/api/v1/approvals", Map.of(
                "id", "approval-single-decision",
                "subjectType", "RECORDING_SESSION",
                "subjectId", "rec-safe-defaults",
                "subjectVersion", 1,
                "approvers", java.util.List.of("reviewer"),
                "reason", "verify one decision"
        ), "system");
        postJson("/api/v1/approvals/approval-single-decision/decisions", Map.of(
                "decision", "APPROVED",
                "reason", "approved once"
        ), "reviewer");
        postJsonExpectingStatus("/api/v1/approvals/approval-single-decision/decisions", Map.of(
                "decision", "REJECTED",
                "reason", "must not overwrite"
        ), "reviewer", 409);
    }

    private JsonNode transitionExtraction(String id, String expectedStatus, long expectedVersion,
                                          String targetStatus) throws Exception {
        String token = issueFencingToken("extraction_task", id, "move to " + targetStatus);
        return postJson("/api/v1/extraction-tasks/" + id + "/transition", Map.of(
                "expectedStatus", expectedStatus,
                "expectedVersion", expectedVersion,
                "targetStatus", targetStatus,
                "reason", "move to " + targetStatus,
                "fencingToken", token
        ), "system");
    }

    private void approveSubject(String approvalId, String subjectType, String subjectId, long subjectVersion)
            throws Exception {
        postJson("/api/v1/approvals", Map.of(
                "id", approvalId,
                "subjectType", subjectType,
                "subjectId", subjectId,
                "subjectVersion", subjectVersion,
                "reason", "approve transition",
                "approvers", java.util.List.of("reviewer")
        ), "system");
        postJson("/api/v1/approvals/" + approvalId + "/decisions", Map.of(
                "decision", "APPROVED",
                "reason", "approved for integration test"
        ), "reviewer");
    }

    private JsonNode transitionReplayExecution(String id, String expectedStatus, long expectedVersion,
                                               String targetStatus) throws Exception {
        String token = issueFencingToken("replay_execution", id, "move to " + targetStatus);
        return postJson("/api/v1/replay-executions/" + id + "/transition", Map.of(
                "expectedStatus", expectedStatus,
                "expectedVersion", expectedVersion,
                "targetStatus", targetStatus,
                "reason", "move to " + targetStatus,
                "fencingToken", token
        ), "system");
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

    private JsonNode transitionRecording(String id, String expectedStatus, long expectedVersion,
                                         String targetStatus) throws Exception {
        String token = issueFencingToken("recording_session", id, "move to " + targetStatus);
        return postJson("/api/v1/recording-sessions/" + id + "/transition", Map.of(
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
