package com.example.runtimemock.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

    private static final String BUSINESS_ID_PATTERN = "^[A-Z0-9]{2,6}-\\d{8}-\\d{3,}$";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ensureDefaultTopology() {
        jdbcTemplate.update("""
                insert into project(id, organization_id, name, created_at)
                select 'proj-default', 'org-default', 'Default Project', current_timestamp
                 where not exists (select 1 from project where id = 'proj-default')
                """);
        jdbcTemplate.update("""
                insert into application(id, project_id, name, created_at)
                select 'app-default', 'proj-default', 'Default Application', current_timestamp
                 where not exists (select 1 from application where id = 'app-default')
                """);
        jdbcTemplate.update("""
                insert into environment(id, application_id, name, type, created_at)
                select 'env-dev', 'app-default', 'dev', 'dev', current_timestamp
                 where not exists (select 1 from environment where id = 'env-dev')
                """);
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
        String executionId = jdbcTemplate.queryForObject("""
                select id from rollout_instance_execution where operation_plan_id = ? limit 1
                """, String.class, operationId);
        assertThat(executionId).matches(BUSINESS_ID_PATTERN);
        String commandId = jdbcTemplate.queryForObject("""
                select command_id from rollout_instance_execution where operation_plan_id = ? limit 1
                """, String.class, operationId);
        assertThat(commandId).matches(BUSINESS_ID_PATTERN);

        assertThat(jdbcTemplate.queryForObject(
                "select status from rule_version where rule_id = ? and version = ?",
                String.class, ruleId, 2)).isEqualTo("ENABLED");
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

        jdbcTemplate.update("""
                update agent_instance
                   set status = 'ACTIVE',
                       lease_expires_at = current_timestamp - interval '1' minute
                 where id = ?
                """, agentId);
        JsonNode maintenance = postJson("/api/v1/control/schedulers/run-once", Map.of(), "system");
        assertThat(maintenance.at("/runtimeLeases/operationPlansAutoUnloaded").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(getJson("/api/v1/details/operation-plans/" + operationId).get("status").asText())
                .isEqualTo("UNLOADED");
        assertThat(jdbcTemplate.queryForObject(
                "select terminal_source from operation_plan where id = ?",
                String.class, operationId)).isEqualTo("AGENT_GONE");
        assertThat(jdbcTemplate.queryForObject(
                "select status from rule_runtime_status where rule_id = ? and instance_id = ?",
                String.class, ruleId, instanceId)).isEqualTo("REMOVED");

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
        assertThat(jdbcTemplate.queryForObject(
                "select status from operation_plan where id = ?", String.class, operationId))
                .isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "select status from rollout_instance_execution where operation_plan_id = ?",
                String.class, operationId)).isEqualTo("PENDING");

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
        jdbcTemplate.update("""
                update operation_plan
                   set status = 'UNLOADED',
                       terminal_source = 'MANUAL',
                       terminal_reason = 'operator requested unload'
                 where id = ?
                """, operationId);

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
        assertThat(jdbcTemplate.queryForObject(
                "select status from operation_plan where id = ?", String.class, operationId))
                .isEqualTo("UNLOADED");
        assertThat(jdbcTemplate.queryForObject(
                "select terminal_source from operation_plan where id = ?", String.class, operationId))
                .isEqualTo("MANUAL");
    }

    @Test
    void abandonsPlanWhenItsTargetInstanceIsCleaned() throws Exception {
        String instanceId = "instance-abandoned-1";
        String agentId = "agent-abandoned-1";
        registerInstanceAndAgent(instanceId, agentId);
        String ruleId = createSimpleRule("Abandoned lifecycle rule", "com.example.AbandonedService");
        String operationId = createOperation(ruleId);
        insertSucceededExecution("rollout-execution-abandoned-1", operationId, instanceId, ruleId);
        jdbcTemplate.update("""
                update agent_instance
                   set status = 'OFFLINE',
                       lease_expires_at = current_timestamp - interval '1' hour,
                       last_heartbeat_at = current_timestamp - interval '1' hour,
                       updated_at = current_timestamp - interval '1' hour
                 where id = ?
                """, agentId);
        jdbcTemplate.update("""
                update instance
                   set status = 'OFFLINE',
                       lease_expires_at = current_timestamp - interval '1' hour,
                       last_seen_at = current_timestamp - interval '1' hour,
                       updated_at = current_timestamp - interval '1' hour
                 where id = ?
                """, instanceId);

        JsonNode maintenance = postJson("/api/v1/control/schedulers/run-once", Map.of(), "system");
        assertThat(maintenance.at("/runtimeCleanup/operationPlansAbandoned").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select status from operation_plan where id = ?", String.class, operationId))
                .isEqualTo("ABANDONED");
        assertThat(jdbcTemplate.queryForObject(
                "select terminal_source from operation_plan where id = ?", String.class, operationId))
                .isEqualTo("INSTANCE_GONE");
        assertThat(jdbcTemplate.queryForObject(
                "select status from instance where id = ?", String.class, instanceId))
                .isEqualTo("ARCHIVED");
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
        jdbcTemplate.update("""
                insert into application(id, project_id, name, created_at)
                values ('app-empty-rollout-options', 'proj-default', 'Empty Rollout Options App', current_timestamp)
                """);
        jdbcTemplate.update("""
                insert into environment(id, application_id, name, type, created_at)
                values ('env-empty-rollout-options', 'app-empty-rollout-options', 'dev', 'dev', current_timestamp)
                """);

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
        jdbcTemplate.update("update operation_plan set status = 'SUCCEEDED', version = 2 where id = ?",
                operationId);
        jdbcTemplate.update("update rule_version set status = 'ENABLED' where rule_id = ? and version = 1",
                ruleId);
        return operationId;
    }

    private void insertSucceededExecution(String executionId, String operationId, String instanceId,
                                          String ruleId) {
        jdbcTemplate.update("""
                insert into rollout_instance_execution(
                    id, rollout_batch_id, operation_plan_id, instance_id, status,
                    expected_agent_version, expected_rule_version, command_id, error_message,
                    started_at, finished_at, version, updated_by, updated_at
                ) values (?, null, ?, ?, 'SUCCEEDED', '0.1.0', 1, null, null,
                          current_timestamp, current_timestamp, 1, 'system', current_timestamp)
                """, executionId, operationId, instanceId);
        jdbcTemplate.update("""
                insert into rule_runtime_status(
                    id, rule_id, rule_version, instance_id, status,
                    hit_count, error_count, last_error, updated_at
                ) values (?, ?, 1, ?, 'ACTIVE', 0, 0, null, current_timestamp)
                """, "runtime-status-" + executionId, ruleId, instanceId);
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
