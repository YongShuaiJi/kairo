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
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:runtime_mock_platform_token;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "runtime-mock.platform.auth.mode=local-token",
        "runtime-mock.platform.auth.bootstrap-token=bootstrap-test-token",
        "runtime-mock.platform.rollout.scheduler.enabled=false"
})
@AutoConfigureMockMvc
class PlatformLocalTokenIntegrationTest {

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
    void returnsUtf8ChineseErrorsFromSecurityAndIdempotencyFilters() throws Exception {
        var unauthorized = mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse();

        assertThat(unauthorized.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(unauthorized.getContentAsString()).contains("需要提供有效的 Bearer Token");

        var invalidIdempotencyKey = mockMvc.perform(post("/api/v1/rules")
                        .header("Authorization", "Bearer bootstrap-test-token")
                        .header("Idempotency-Key", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn()
                .getResponse();

        assertThat(invalidIdempotencyKey.getCharacterEncoding()).isEqualToIgnoringCase("UTF-8");
        assertThat(invalidIdempotencyKey.getContentAsString())
                .contains("Idempotency-Key 不能为空且长度不能超过 255 个字符");
    }

    @Test
    void requiresBearerTokenAndScopesAgentTokens() throws Exception {
        mockMvc.perform(get("/api/v1/instances")
                        .header("X-Actor", "system"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/instances")
                        .header("Authorization", "Bearer bootstrap-test-token"))
                .andExpect(status().isOk());

        JsonNode permanent = postJson("/api/v1/auth/tokens", Map.of(
                "username", "system"
        ), "bootstrap-test-token");
        assertThat(permanent.path("expiresAt").isNull()).isTrue();
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + permanent.path("token").asText()))
                .andExpect(status().isOk());

        JsonNode newUser = postJson("/api/v1/auth/tokens", Map.of(
                "username", " 测试 ",
                "displayName", " 测试用户 ",
                "ttlSeconds", 3600
        ), "bootstrap-test-token");
        assertThat(newUser.path("subjectId").asText()).isEqualTo("测试");
        assertThat(newUser.path("displayName").asText()).isEqualTo("测试用户");
        String newUserMeResponse = mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + newUser.path("token").asText()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode newUserMe = objectMapper.readTree(newUserMeResponse);
        assertThat(newUserMe.path("subject").asText()).isEqualTo("测试");
        assertThat(newUserMe.path("displayName").asText()).isEqualTo("测试用户");
        assertThat(newUserMe.path("roles")).isEmpty();
        assertThat(newUserMe.path("capabilities")).isEmpty();

        createAgent("token-instance-1", "token-agent-1");
        createAgent("token-instance-2", "token-agent-2");

        JsonNode issued = postJson("/api/v1/auth/tokens", Map.of(
                "subjectType", "AGENT",
                "subjectId", "token-agent-1",
                "displayName", "integration agent token",
                "ttlSeconds", 3600
        ), "bootstrap-test-token");
        String tokenId = issued.get("id").asText();
        String agentToken = issued.get("token").asText();
        assertThat(agentToken).isNotBlank();

        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/instances")
                        .header("Authorization", "Bearer " + agentToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/agents/token-agent-1/heartbeat")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"metrics\":{\"commands\":0}}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/agents/token-agent-1/commands/next")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseSeconds\":30}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/agents/token-agent-2/commands/next")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseSeconds\":30}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/auth/tokens/{id}", tokenId)
                        .header("Authorization", "Bearer bootstrap-test-token"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/agents/token-agent-1/commands/next")
                        .header("Authorization", "Bearer " + agentToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"leaseSeconds\":30}"))
                .andExpect(status().isUnauthorized());
    }

    private void createAgent(String instanceId, String agentId) throws Exception {
        postJson("/api/v1/instances", Map.of(
                "id", instanceId,
                "applicationId", "app-default",
                "environmentId", "env-dev",
                "hostname", "localhost",
                "processId", "1",
                "runtime", "java-21",
                "labels", Map.of(),
                "reason", "token integration test"
        ), "bootstrap-test-token");
        postJson("/api/v1/agents", Map.of(
                "id", agentId,
                "instanceId", instanceId,
                "agentVersion", "0.1.0",
                "bootstrapVersion", "0.1.0",
                "listenHost", "127.0.0.1",
                "listenPort", 18080,
                "tokenHash", "legacy-unused",
                "capabilities", List.of("JAVA_METHOD"),
                "reason", "token integration test"
        ), "bootstrap-test-token");
    }

    private JsonNode postJson(String path, Object body, String token) throws Exception {
        String response = mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(body)))
                .andExpect(status().is2xxSuccessful())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(response);
    }
}
