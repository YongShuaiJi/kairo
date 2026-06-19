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

        JsonNode datasource = postJson("/api/v1/datasources", Map.of(
                "id", "web-contract-datasource",
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "datasourceType", "POSTGRESQL",
                "name", "Contract datasource",
                "config", Map.of("jdbcUrl", "jdbc:postgresql://db/example", "password", "must-never-be-returned"),
                "reason", "web contract test"
        ));
        assertThat(datasource.toString()).doesNotContain("config_json").doesNotContain("must-never-be-returned");

        mockMvc.perform(get("/api/v1/query/instances")
                        .param("q", "contract-host")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].id").value("web-contract-instance"));

        mockMvc.perform(get("/api/v1/details/instances/web-contract-instance")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostname").value("contract-host"))
                .andExpect(jsonPath("$.allowed_actions").isArray());

        postJson("/api/v1/rules", Map.ofEntries(
                Map.entry("id", "web-contract-rule"),
                Map.entry("applicationId", "app-default"),
                Map.entry("environmentId", "env-dev"),
                Map.entry("name", "Web contract target"),
                Map.entry("versionStatus", "DRAFT"),
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
                .andExpect(jsonPath("$[0].class_name").value("com.example.contract.ContractService"))
                .andExpect(jsonPath("$[0].method_name").value("execute"));
        mockMvc.perform(get("/api/v1/targets/search")
                        .param("q", "ContractService")
                        .param("applicationId", "app-default")
                        .param("environmentId", "env-prod")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());

        JsonNode issued = postJson("/api/v1/auth/tokens", Map.of(
                "subjectType", "USER",
                "subjectId", "reviewer",
                "displayName", "Web contract reviewer",
                "ttlSeconds", 3600
        ));
        assertThat(issued.path("token").asText()).isNotBlank();

        String tokenList = mockMvc.perform(get("/api/v1/query/tokens")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andReturn().getResponse().getContentAsString();
        assertThat(tokenList).doesNotContain("token_hash").doesNotContain(issued.path("token").asText());

        String agents = mockMvc.perform(get("/api/v1/query/agents").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(agents).doesNotContain("token_hash").doesNotContain("must-never-be-returned");

        String datasources = mockMvc.perform(get("/api/v1/query/datasources").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(datasources).doesNotContain("config_json").doesNotContain("must-never-be-returned");

        mockMvc.perform(get("/api/v1/dashboard/overview").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts").isMap())
                .andExpect(jsonPath("$.recentAudits").isArray());

        mockMvc.perform(get("/api/v1/this-route-does-not-exist").header("X-Actor", "system"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
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
}
