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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:runtime_mock_platform_web_contract;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformWebContractIntegrationTest {

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
    void supportsTheWebConsoleContractWithoutLeakingCredentials() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("system"))
                .andExpect(jsonPath("$.capabilities").isArray());

        postJson("/api/v1/instances", Map.of(
                "id", "web-contract-instance",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "hostname", "contract-host",
                "processId", "42",
                "runtime", "java-21",
                "labels", Map.of("source", "web-contract"),
                "reason", "web contract test"
        ));
        JsonNode agent = postJson("/api/v1/agents", Map.of(
                "id", "web-contract-agent",
                "instanceId", "web-contract-instance",
                "agentVersion", "0.1.0",
                "bootstrapVersion", "0.1.0",
                "listenHost", "127.0.0.1",
                "listenPort", 18080,
                "tokenHash", "must-never-be-returned",
                "capabilities", java.util.List.of("JAVA_METHOD"),
                "reason", "web contract test"
        ));
        assertThat(agent.toString()).doesNotContain("token_hash").doesNotContain("must-never-be-returned");

        mockMvc.perform(get("/api/v1/query/instances")
                .param("q", "contract-host")
                .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("web-contract-instance"));

        mockMvc.perform(get("/api/v1/details/instances/web-contract-instance")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("contract-host"))
                .andExpect(jsonPath("$.allowed_actions").isArray());

        postJson("/api/v1/rules", Map.ofEntries(
                Map.entry("applicationId", "app-default"),
                Map.entry("environmentId", "env-dev"),
                Map.entry("name", "Web contract target"),
                Map.entry("versionStatus", "ENABLED"),
                Map.entry("riskLevel", "LOW"),
                Map.entry("script", Map.of("phase", "RETURN", "script", "return mock.proceed()")),
                Map.entry("targets", java.util.List.of(Map.of(
                        "protocol", "JAVA_METHOD",
                        "className", "com.example.contract.ContractService",
                        "methodName", "execute"
                ))),
                Map.entry("reason", "web contract target search")
        ));
        mockMvc.perform(get("/api/v1/targets/search")
                        .param("q", "ContractService")
                        .param("applicationId", "app-default")
                        .param("environmentId", "env-dev")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/v1/targets/search")
                        .param("q", "ContractService")
                        .param("applicationId", "app-default")
                        .param("environmentId", "env-prod")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        JsonNode issued = postJson("/api/v1/auth/tokens", Map.of(
                "username", "reviewer",
                "expiresAt", java.time.Instant.now().plus(Duration.ofHours(1)).toString()
        ));
        assertThat(issued.path("token").asText()).isNotBlank();
        assertThat(issued.path("subjectType").asText()).isEqualTo("USER");
        assertThat(issued.path("subjectId").asText()).isEqualTo("reviewer");
        assertThat(issued.path("displayName").asText()).isEqualTo("reviewer");

        String tokenList = mockMvc.perform(get("/api/v1/query/tokens")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].subject_id").value("reviewer"))
                .andExpect(jsonPath("$.items[0].status").value("VALID"))
                .andReturn().getResponse().getContentAsString();
        assertThat(tokenList).doesNotContain("token_hash").doesNotContain(issued.path("token").asText());

        String tokenDetail = mockMvc.perform(get("/api/v1/details/tokens/" + issued.path("id").asText())
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject_id").value("reviewer"))
                .andExpect(jsonPath("$.expires_at").exists())
                .andExpect(jsonPath("$.status").doesNotExist())
                .andExpect(jsonPath("$.subject_type").doesNotExist())
                .andExpect(jsonPath("$.created_at").doesNotExist())
                .andExpect(jsonPath("$.last_used_at").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(tokenDetail)
                .doesNotContain("token_hash")
                .doesNotContain("display_name")
                .doesNotContain(issued.path("token").asText());

        JsonNode renewed = postJson("/api/v1/auth/tokens/" + issued.path("id").asText() + "/renew", Map.of(
                "expiresAt", java.time.Instant.now().plus(Duration.ofHours(2)).toString()
        ));
        assertThat(renewed.path("status").asText()).isEqualTo("VALID");
        assertThat(renewed.has("token")).isFalse();

        JsonNode permanentRenewed = postJson("/api/v1/auth/tokens/" + issued.path("id").asText() + "/renew", Map.of());
        assertThat(permanentRenewed.path("status").asText()).isEqualTo("VALID");
        assertThat(permanentRenewed.path("expires_at").isNull()).isTrue();
        assertThat(permanentRenewed.has("token")).isFalse();

        JsonNode permanentIssued = postJson("/api/v1/auth/tokens", Map.of(
                "username", "system"
        ));
        assertThat(permanentIssued.path("expiresAt").isNull()).isTrue();

        String agents = mockMvc.perform(get("/api/v1/query/agents").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(agents).doesNotContain("token_hash").doesNotContain("must-never-be-returned");

        mockMvc.perform(get("/api/v1/dashboard/overview").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts").isMap())
                .andExpect(jsonPath("$.recentAudits").isArray());

        mockMvc.perform(get("/api/v1/this-route-does-not-exist").header("X-Actor", "system"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void registersRuntimeAssignsEnvironmentAndDiscoversExactJvmTargets() throws Exception {
        JsonNode registration = postJson("/api/v1/agent-registrations/self", Map.ofEntries(
                Map.entry("projectName", "runtime-mock"),
                Map.entry("applicationName", "runtime-mock-demo"),
                Map.entry("hostname", "runtime-contract-host"),
                Map.entry("processId", "4242"),
                Map.entry("processStartId", "runtime-contract-host:4242:123456789"),
                Map.entry("jvmStartedAtEpochMillis", 123456789L),
                Map.entry("runtime", "java-21"),
                Map.entry("javaVersion", "21.0.9"),
                Map.entry("loadMode", "premain"),
                Map.entry("agentVersion", "0.1.0"),
                Map.entry("bootstrapVersion", "0.1.0"),
                Map.entry("listenHost", "127.0.0.1"),
                Map.entry("listenPort", 18080),
                Map.entry("capabilities", java.util.List.of("DISCOVER_TARGETS", "APPLY_RULE"))
        ));
        String instanceId = registration.path("instanceId").asText();
        String agentId = registration.path("agentId").asText();
        String applicationId = registration.path("applicationId").asText();
        assertThat(registration.path("projectName").asText()).isEqualTo("runtime-mock");
        assertThat(registration.path("applicationName").asText()).isEqualTo("runtime-mock-demo");
        assertThat(applicationId).startsWith("application-");
        assertThat(registration.path("status").asText()).isEqualTo("PENDING_ASSIGNMENT");
        assertThat(registration.path("environmentId").isNull()).isTrue();

        String environmentId = jdbcTemplate.queryForObject("""
                select id from environment
                 where application_id = ? and type = 'dev'
                """, String.class, applicationId);
        JsonNode assigned = postJson("/api/v1/instances/" + instanceId + "/environment", Map.of(
                "environmentId", environmentId
        ));
        assertThat(assigned.path("environment_id").asText()).isEqualTo(environmentId);
        assertThat(assigned.path("registration_status").asText()).isEqualTo("ASSIGNED");

        mockMvc.perform(get("/api/v1/query/instances")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].project_name").value("runtime-mock"))
                .andExpect(jsonPath("$.items[0].application_name").value("runtime-mock-demo"))
                .andExpect(jsonPath("$.items[0].environment_name").value("dev"));

        jdbcTemplate.update("update agent_instance set status = 'OFFLINE' where id <> ?", agentId);
        CompletableFuture<String> discovery = CompletableFuture.supplyAsync(() -> {
            try {
                return mockMvc.perform(get("/api/v1/targets/search")
                                .param("q", "OrderService")
                                .param("applicationId", applicationId)
                                .param("environmentId", environmentId)
                                .header("X-Actor", "system"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });

        JsonNode command = waitForCommand(agentId, Duration.ofSeconds(3));
        assertThat(command.path("command_type").asText()).isEqualTo("DISCOVER_TARGETS");
        postJson("/api/v1/agent-commands/" + command.path("id").asText() + "/ack", Map.of(
                "status", "ACKED",
                "result", Map.of("targets", java.util.List.of(Map.ofEntries(
                        Map.entry("classId", "loader-1:com.example.demo.OrderService"),
                        Map.entry("className", "com.example.demo.OrderService"),
                        Map.entry("classLoaderId", "loader-1"),
                        Map.entry("methodName", "calculateScore"),
                        Map.entry("descriptor", "(I)I"),
                        Map.entry("parameterTypes", java.util.List.of("int")),
                        Map.entry("returnType", "int")
                )))
        ));

        JsonNode targets = objectMapper.readTree(discovery.get(5, TimeUnit.SECONDS));
        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).path("className").asText())
                .isEqualTo("com.example.demo.OrderService");
        assertThat(targets.get(0).path("classId").asText())
                .isEqualTo("loader-1:com.example.demo.OrderService");
        assertThat(targets.get(0).path("descriptor").asText()).isEqualTo("(I)I");
        assertThat(targets.get(0).path("agentCount").asInt()).isEqualTo(1);
        assertThat(targets.get(0).path("instanceCount").asInt()).isEqualTo(1);
    }

    @Test
    void rejectsMissingOrCrossScopedApplicationEnvironmentInsteadOfInventingDefaults() throws Exception {
        mockMvc.perform(post("/api/v1/rules")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", "missing-rule-environment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "applicationId", "app-default",
                                "name", "Must not receive a default environment"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIELD_REQUIRED"))
                .andExpect(jsonPath("$.message").value("缺少必填字段：environmentId"));

        mockMvc.perform(post("/api/v1/rules")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", "cross-scoped-rule-environment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "applicationId", "app-default",
                                "environmentId", "environment-does-not-exist",
                                "name", "Invalid environment"
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ENVIRONMENT"))
                .andExpect(jsonPath("$.message").value("所选环境不存在，或不属于当前应用"));

        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from rule where name = ?",
                Integer.class,
                "Must not receive a default environment");
        assertThat(count).isZero();
    }

    @Test
    void validatesAndExecutesScriptsWithTheProductionCompiler() throws Exception {
        mockMvc.perform(post("/api/v1/scripts/validate")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "script", "return mock.returnValue('contract-ok')"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.diagnostics").isEmpty());

        mockMvc.perform(post("/api/v1/scripts/test")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "script", "log.info('executed'); return mock.returnValue('contract-ok')",
                                "input", Map.of("phase", "RETURN", "args", java.util.List.of(), "result", "original")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.decision").value("RETURN"))
                .andExpect(jsonPath("$.output").value("contract-ok"))
                .andExpect(jsonPath("$.logs[0]").value("INFO executed"));

        mockMvc.perform(post("/api/v1/scripts/validate")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "script", "System.exit(0)"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].severity").value("error"));
    }

    private JsonNode postJson(String path, Object body) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private JsonNode waitForCommand(String agentId, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode response = postJson("/api/v1/agents/" + agentId + "/commands/next", Map.of(
                    "leaseSeconds", 30
            ));
            if (!"NO_COMMAND".equals(response.path("status").asText())) {
                return response;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Agent did not receive a discovery command before timeout");
    }
}
