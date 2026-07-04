package com.example.runtimemock.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.runtimemock.platform.persistence.mapper.TestPlatformMapper;
import org.junit.jupiter.api.BeforeEach;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformControllerIntegrationTest {

    private static final String BUSINESS_ID_PATTERN = "^[A-Z0-9]{2,6}-\\d{8}-\\d{3,}$";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TestPlatformMapper testPlatformMapper;

    @BeforeEach
    void ensureDefaultTopology() {
        testPlatformMapper.ensureDefaultProject();
        testPlatformMapper.ensureDefaultApplication();
        testPlatformMapper.ensureDefaultEnvironment();
    }

    @Test
    void unloadsPublishedRuleAndCompletesOnlyAfterAgentAck() throws Exception {
        String instanceId = "instance-unload-1";
        String agentId = "agent-unload-1";

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
        JsonNode rule = postJson("/api/v1/rules", Map.ofEntries(
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
        String ruleId = rule.get("id").asText();
        assertThat(ruleId).matches(BUSINESS_ID_PATTERN);
        JsonNode operation = postJson("/api/v1/operation-plans", Map.of(
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
        String operationId = operation.get("id").asText();
        assertThat(operationId).matches(BUSINESS_ID_PATTERN);
        testPlatformMapper.insertSucceededRolloutExecution("rollout-execution-unload-1", operationId, instanceId);
        testPlatformMapper.markOperationSucceeded(operationId);
        testPlatformMapper.insertActiveRuleRuntimeStatus("runtime-status-unload-1", ruleId, instanceId);

        String fencingToken = issueFencingToken("operation_plan", operationId, "unload rule");
        JsonNode unload = postJson("/api/v1/operation-plans/" + operationId + "/unload", Map.of(
                "expectedStatus", "SUCCEEDED",
                "expectedVersion", 2,
                "fencingToken", fencingToken,
                "reason", "integration test unload"
        ), "system");
        assertThat(unload.at("/operationPlan/status").asText()).isEqualTo("UNLOADING");
        assertThat(unload.get("commandCount").asInt()).isEqualTo(1);
        assertThat(unload.at("/unloadExecution/id").asText()).matches(BUSINESS_ID_PATTERN);
        assertThat(unload.at("/rollbackExecution/id").asText()).matches(BUSINESS_ID_PATTERN);

        JsonNode command = postJson("/api/v1/agents/" + agentId + "/commands/next",
                Map.of("leaseSeconds", 60), "system");
        assertThat(command.get("id").asText()).matches(BUSINESS_ID_PATTERN);
        assertThat(command.get("command_type").asText()).isEqualTo("RESET_CLASS");
        assertThat(command.at("/payload/classId").asText()).isEqualTo("com.example.UnloadService");

        postJson("/api/v1/agent-commands/" + command.get("id").asText() + "/ack", Map.of(
                "status", "ACKED",
                "result", Map.of("removedRuleIds", java.util.List.of(ruleId + ":1")),
                "reason", "class reset completed"
        ), "system");

        assertThat(getJson("/api/v1/details/operation-plans/" + operationId)
                .get("status").asText()).isEqualTo("UNLOADED");
        assertThat(testPlatformMapper.ruleRuntimeStatusById("runtime-status-unload-1")).isEqualTo("REMOVED");
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
        String ruleId = rule.get("id").asText();
        assertThat(ruleId).matches(BUSINESS_ID_PATTERN);
        assertThat(rule.get("latest_version").asLong()).isEqualTo(1);

        JsonNode ruleVersion = postJson("/api/v1/rules/" + ruleId + "/versions", Map.of(
                "riskLevel", "HIGH",
                "versionStatus", "ENABLED",
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
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "planType", "RULE_ROLLOUT",
                "resourceType", "rule",
                "resourceId", ruleId,
                "resourceVersion", 2,
                "strategy", Map.of(
                        "targetMode", "ALL_ACTIVE_INSTANCES",
                        "automaticUnload", true
                ),
                "reason", "create operation"
        ), "system");
        assertThat(operation.get("status").asText()).isEqualTo("DRAFT");
        String operationId = operation.get("id").asText();
        assertThat(operationId).matches(BUSINESS_ID_PATTERN);
        JsonNode runningOperation = transitionOperation(operationId, "DRAFT", 1, "RUNNING");
        assertThat(runningOperation.get("version").asLong()).isEqualTo(2);
        JsonNode maintenance = postJson("/api/v1/control/schedulers/run-once", Map.of(), "system");
        assertThat(maintenance.at("/rollout/targetsCaptured").asInt()).isGreaterThanOrEqualTo(1);
        String executionId = testPlatformMapper.firstRolloutExecutionId(operationId);
        assertThat(executionId).matches(BUSINESS_ID_PATTERN);
        String commandId = testPlatformMapper.firstRolloutExecutionCommandId(operationId);
        assertThat(commandId).matches(BUSINESS_ID_PATTERN);

        assertThat(testPlatformMapper.ruleVersionStatus(ruleId, 2)).isEqualTo("ENABLED");
        assertThat(getJson("/api/v1/control/health").get("operationPlanCount").asLong()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void allowsClientProvidedInstanceIdAndGeneratesBusinessIdWhenMissing() throws Exception {
        JsonNode explicit = postJson("/api/v1/instances", Map.of(
                "id", "client-runtime-instance-1",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "hostname", "client-instance-host",
                "processId", "9101",
                "runtime", "java-21",
                "labels", Map.of("source", "client"),
                "reason", "client registered instance id"
        ), "system");
        assertThat(explicit.get("id").asText()).isEqualTo("client-runtime-instance-1");

        JsonNode generated = postJson("/api/v1/instances", Map.of(
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "hostname", "generated-instance-host",
                "processId", "9102",
                "runtime", "java-21",
                "labels", Map.of("source", "platform"),
                "reason", "platform generated instance id"
        ), "system");
        assertThat(generated.get("id").asText()).matches(BUSINESS_ID_PATTERN);
    }

    @Test
    void autoUnloadsPlanWhenAgentIsGoneAndRestoresItOnAgentRegistration() throws Exception {
        String instanceId = "instance-agent-gone-1";
        String agentId = "agent-agent-gone-1";
        registerInstanceAndAgent(instanceId, agentId);
        String ruleId = createSimpleRule("Agent gone lifecycle rule", "com.example.AgentGoneService");
        String operationId = createOperation(ruleId);
        insertSucceededExecution("rollout-execution-agent-gone-1", operationId, instanceId, ruleId);

        testPlatformMapper.expireAgentLease(agentId);
        JsonNode maintenance = postJson("/api/v1/control/schedulers/run-once", Map.of(), "system");
        assertThat(maintenance.at("/runtimeLeases/operationPlansAutoUnloaded").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(getJson("/api/v1/details/operation-plans/" + operationId).get("status").asText())
                .isEqualTo("UNLOADED");
        assertThat(testPlatformMapper.operationPlanTerminalSource(operationId)).isEqualTo("AGENT_GONE");
        assertThat(testPlatformMapper.ruleRuntimeStatus(ruleId, instanceId)).isEqualTo("REMOVED");

        JsonNode registration = postJson("/api/v1/agent-registrations/self", Map.ofEntries(
                Map.entry("instanceId", instanceId),
                Map.entry("projectName", "Default Project"),
                Map.entry("applicationName", "Default Application"),
                Map.entry("environmentId", "env-dev"),
                Map.entry("hostname", "agent-gone-host"),
                Map.entry("processId", "9901"),
                Map.entry("processStartId", "agent-gone-host:9901:1"),
                Map.entry("runtime", "java-21"),
                Map.entry("javaVersion", "21"),
                Map.entry("loadMode", "reload"),
                Map.entry("agentVersion", "0.1.0"),
                Map.entry("bootstrapVersion", "0.1.0"),
                Map.entry("listenHost", "127.0.0.1"),
                Map.entry("listenPort", 19091),
                Map.entry("capabilities", java.util.List.of("APPLY_RULE", "RESET_CLASS"))
        ), "system");
        assertThat(registration.get("restoredOperationPlans").asInt()).isEqualTo(1);
        assertThat(testPlatformMapper.operationPlanStatus(operationId)).isEqualTo("RUNNING");
        assertThat(testPlatformMapper.rolloutExecutionStatusByOperation(operationId)).isEqualTo("PENDING");

        postJson("/api/v1/control/schedulers/run-once", Map.of(), "system");
        JsonNode command = postJson("/api/v1/agents/" + agentId + "/commands/next",
                Map.of("leaseSeconds", 60), "system");
        assertThat(command.get("id").asText()).matches(BUSINESS_ID_PATTERN);
        assertThat(command.get("command_type").asText()).isEqualTo("APPLY_RULE");
        assertThat(command.at("/payload/operationPlanId").asText()).isEqualTo(operationId);
    }

    @Test
    void doesNotRestoreManuallyUnloadedPlanWhenAgentRegistersAgain() throws Exception {
        String instanceId = "instance-manual-unload-1";
        String agentId = "agent-manual-unload-1";
        registerInstanceAndAgent(instanceId, agentId);
        String ruleId = createSimpleRule("Manual unload lifecycle rule", "com.example.ManualUnloadService");
        String operationId = createOperation(ruleId);
        insertSucceededExecution("rollout-execution-manual-unload-1", operationId, instanceId, ruleId);
        testPlatformMapper.markOperationManuallyUnloaded(operationId);

        JsonNode registration = postJson("/api/v1/agent-registrations/self", Map.ofEntries(
                Map.entry("instanceId", instanceId),
                Map.entry("projectName", "Default Project"),
                Map.entry("applicationName", "Default Application"),
                Map.entry("environmentId", "env-dev"),
                Map.entry("hostname", "manual-unload-host"),
                Map.entry("processId", "9902"),
                Map.entry("processStartId", "manual-unload-host:9902:1"),
                Map.entry("runtime", "java-21"),
                Map.entry("javaVersion", "21"),
                Map.entry("loadMode", "reload"),
                Map.entry("agentVersion", "0.1.0"),
                Map.entry("bootstrapVersion", "0.1.0"),
                Map.entry("listenHost", "127.0.0.1"),
                Map.entry("listenPort", 19092),
                Map.entry("capabilities", java.util.List.of("APPLY_RULE", "RESET_CLASS"))
        ), "system");
        assertThat(registration.get("restoredOperationPlans").asInt()).isZero();
        assertThat(testPlatformMapper.operationPlanStatus(operationId)).isEqualTo("UNLOADED");
        assertThat(testPlatformMapper.operationPlanTerminalSource(operationId)).isEqualTo("MANUAL");
    }

    @Test
    void abandonsPlanWhenItsTargetInstanceIsCleaned() throws Exception {
        String instanceId = "instance-abandoned-1";
        String agentId = "agent-abandoned-1";
        registerInstanceAndAgent(instanceId, agentId);
        String ruleId = createSimpleRule("Abandoned lifecycle rule", "com.example.AbandonedService");
        String operationId = createOperation(ruleId);
        insertSucceededExecution("rollout-execution-abandoned-1", operationId, instanceId, ruleId);
        testPlatformMapper.markAgentOfflineExpired(agentId);
        testPlatformMapper.markInstanceOfflineExpired(instanceId);

        JsonNode maintenance = postJson("/api/v1/control/schedulers/run-once", Map.of(), "system");
        assertThat(maintenance.at("/runtimeCleanup/operationPlansAbandoned").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(testPlatformMapper.operationPlanStatus(operationId)).isEqualTo("ABANDONED");
        assertThat(testPlatformMapper.operationPlanTerminalSource(operationId)).isEqualTo("INSTANCE_GONE");
        assertThat(testPlatformMapper.instanceStatus(instanceId)).isEqualTo("ARCHIVED");
    }

    @Test
    void marksSidecarsOfflineWhenAttachExecutorLeaseExpires() throws Exception {
        String instanceId = "instance-expired-executor-1";
        postJson("/api/v1/instances", Map.of(
                "id", instanceId,
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "hostname", "expired-executor-host",
                "processId", "9001",
                "runtime", "java-21",
                "labels", Map.of("purpose", "expired-executor"),
                "reason", "register expired executor test instance"
        ), "system");
        testPlatformMapper.insertExpiredAttachExecutor();
        testPlatformMapper.insertExpiredAttachExecutorTarget(instanceId);
        testPlatformMapper.insertExpiredSidecar(instanceId);

        JsonNode maintenance = postJson("/api/v1/control/schedulers/run-once", Map.of(), "system");
        assertThat(maintenance.at("/runtimeLeases/executorsOffline").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(maintenance.at("/runtimeLeases/targetsOffline").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(maintenance.at("/runtimeLeases/sidecarsOffline").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(testPlatformMapper.attachExecutorStatus("runtime-mock-expired-executor"))
                .isEqualTo("OFFLINE");
        assertThat(testPlatformMapper.attachExecutorTargetStatus("runtime-mock-expired-executor", instanceId))
                .isEqualTo("OFFLINE");
        assertThat(testPlatformMapper.sidecarStatus("sidecar-expired-executor-1")).isEqualTo("OFFLINE");
    }

    @Test
    void rejectsClientProvidedDeliveryId() throws Exception {
        String ruleId = createSimpleRule("Client id rejected rule", "com.example.ClientIdRejectedService");

        postJsonExpectingStatus("/api/v1/operation-plans", Map.of(
                "id", "operation-client-provided-1",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "planType", "RULE_ROLLOUT",
                "resourceType", "rule",
                "resourceId", ruleId,
                "resourceVersion", 1,
                "strategy", Map.of("targetMode", "ALL_ACTIVE_INSTANCES"),
                "reason", "client id should be rejected"
        ), "system", 400);
    }

    @Test
    void rejectsClientProvidedRuleId() throws Exception {
        postJsonExpectingStatus("/api/v1/rules", Map.ofEntries(
                Map.entry("id", "rule-client-provided-1"),
                Map.entry("applicationId", "app-default"),
                Map.entry("environmentId", "env-dev"),
                Map.entry("name", "Client provided rule id"),
                Map.entry("riskLevel", "LOW"),
                Map.entry("matcher", Map.of()),
                Map.entry("script", Map.of("type", "RETURN", "value", "mocked")),
                Map.entry("targets", java.util.List.of(Map.of(
                        "protocol", "JAVA_METHOD",
                        "className", "com.example.ClientProvidedRuleIdService",
                        "methodName", "query"
                ))),
                Map.entry("reason", "client rule id should be rejected")
        ), "system", 400);
    }

    @Test
    void rejectsDisabledRuleRolloutAndHidesDisabledVersionsFromOptions() throws Exception {
        String ruleId = createSimpleRule("Disabled rollout rule", "com.example.DisabledRolloutService");
        postJson("/api/v1/rules/" + ruleId + "/versions/1/disable", Map.of(), "system");

        JsonNode versions = getJson("/api/v1/query/rule-versions?page=0&size=200&q=" + ruleId);
        assertThat(versions.get("total").asInt()).isZero();
        assertThat(versions.get("items")).isEmpty();

        postJsonExpectingStatus("/api/v1/operation-plans", Map.of(
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "planType", "RULE_ROLLOUT",
                "resourceType", "rule",
                "resourceId", ruleId,
                "resourceVersion", 1,
                "strategy", Map.of("targetMode", "ALL_ACTIVE_INSTANCES"),
                "reason", "disabled rule should be rejected"
        ), "system", 400);
    }

    @Test
    void rolloutApplicationOptionsExcludeEmptyTopologyApplications() throws Exception {
        testPlatformMapper.insertEmptyRolloutOptionsApplication();
        testPlatformMapper.insertEmptyRolloutOptionsEnvironment();

        JsonNode options = getJson("/api/v1/query/rollout-applications?page=0&size=200&q=Empty%20Rollout%20Options");

        assertThat(options.get("total").asInt()).isZero();
        assertThat(options.get("items")).isEmpty();
    }

    @Test
    void rolloutEnvironmentOptionsDriveApplications() throws Exception {
        createSimpleRule("Rollout options rule", "com.example.RolloutOptionsService");

        JsonNode environments = getJson("/api/v1/query/rollout-environments?page=0&size=200&q=dev");
        assertThat(environments.get("total").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(environments.get("items").get(0).get("id").asText()).isEqualTo("dev");

        JsonNode applications = getJson("/api/v1/query/rollout-applications?page=0&size=200&q=Default");
        assertThat(applications.get("total").asInt()).isGreaterThanOrEqualTo(1);
        boolean foundDefaultDevApplication = false;
        for (JsonNode item : applications.get("items")) {
            if ("app-default".equals(item.get("id").asText())
                    && "dev".equals(item.get("environment_key").asText())
                    && "env-dev".equals(item.get("environment_id").asText())) {
                foundDefaultDevApplication = true;
                break;
            }
        }
        assertThat(foundDefaultDevApplication).isTrue();
    }

    private void registerInstanceAndAgent(String instanceId, String agentId) throws Exception {
        postJson("/api/v1/instances", Map.of(
                "id", instanceId,
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "hostname", instanceId + "-host",
                "processId", "9001",
                "runtime", "java-21",
                "labels", Map.of("purpose", instanceId),
                "reason", "register lifecycle test instance"
        ), "system");
        postJson("/api/v1/agents", Map.of(
                "id", agentId,
                "instanceId", instanceId,
                "agentVersion", "0.1.0",
                "bootstrapVersion", "0.1.0",
                "listenHost", "127.0.0.1",
                "listenPort", 18091,
                "tokenHash", agentId + "-token",
                "capabilities", java.util.List.of("APPLY_RULE", "RESET_CLASS"),
                "reason", "register lifecycle test agent"
        ), "system");
    }

    private String createSimpleRule(String name, String className) throws Exception {
        JsonNode rule = postJson("/api/v1/rules", Map.ofEntries(
                Map.entry("applicationId", "app-default"),
                Map.entry("environmentId", "env-dev"),
                Map.entry("name", name),
                Map.entry("riskLevel", "LOW"),
                Map.entry("matcher", Map.of()),
                Map.entry("script", Map.of("type", "RETURN", "value", "mocked")),
                Map.entry("governance", Map.of()),
                Map.entry("targets", java.util.List.of(Map.of(
                        "protocol", "JAVA_METHOD",
                        "className", className,
                        "methodName", "query",
                        "matcher", Map.of("classId", className)
                ))),
                Map.entry("capabilities", java.util.List.of("EARLY_RETURN")),
                Map.entry("reason", "create lifecycle test rule")
        ), "system");
        String ruleId = rule.get("id").asText();
        assertThat(ruleId).matches(BUSINESS_ID_PATTERN);
        return ruleId;
    }

    private String createOperation(String ruleId) throws Exception {
        JsonNode operation = postJson("/api/v1/operation-plans", Map.of(
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
                "reason", "create lifecycle test operation"
        ), "system");
        String operationId = operation.get("id").asText();
        assertThat(operationId).matches(BUSINESS_ID_PATTERN);
        testPlatformMapper.markOperationSucceeded(operationId);
        testPlatformMapper.enableRuleVersion(ruleId);
        return operationId;
    }

    private void insertSucceededExecution(String executionId, String operationId, String instanceId,
                                          String ruleId) {
        testPlatformMapper.insertSucceededRolloutExecution(executionId, operationId, instanceId);
        testPlatformMapper.insertActiveRuleRuntimeStatus("runtime-status-" + executionId, ruleId, instanceId);
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
