package com.example.kairo.platform;

import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;5.1 / &sect;8 permission matrix: API-token scope narrowing and the
 * per-token concurrent automation-session limit. A scoped token can only exercise
 * capabilities in its scope, even when the subject holds them; the session limit
 * bounds concurrent AI sessions per token.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_scope;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "kairo.platform.auth.mode=local-token",
        "kairo.platform.auth.bootstrap-token=bootstrap-test-token",
        "kairo.platform.rollout.scheduler.enabled=false"
})
@AutoConfigureMockMvc
class V16TokenScopeIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TestPlatformMapper fixtures;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
    }

    private String issueToken(String username, String scopeJson, String source, String maxSessions) throws Exception {
        StringBuilder body = new StringBuilder("{\"username\":\"" + username + "\",\"ttlSeconds\":3600");
        if (scopeJson != null) {
            body.append(",\"scope\":").append(scopeJson);
        }
        if (source != null) {
            body.append(",\"source\":\"").append(source).append("\"");
        }
        if (maxSessions != null) {
            body.append(",\"maxSessions\":").append(maxSessions);
        }
        body.append("}");
        MvcResult result = mockMvc.perform(post("/api/v1/auth/tokens")
                        .header("Authorization", "Bearer bootstrap-test-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().is2xxSuccessful())
                .andReturn();
        return result.getResponse().getContentAsString().split("\"token\":\"")[1].split("\"")[0];
    }

    @Test
    void scopedTokenIsDeniedOutOfScopeCapability() throws Exception {
        // Business user "ai-scoped" holds RULE_MANAGE, but the token scope narrows to INSTANCE_MANAGE only.
        String scoped = issueToken("ai-scoped", "[\"INSTANCE_MANAGE\"]", "mcp", null);

        mockMvc.perform(get("/api/v1/apps/app-default/script-policy")
                        .header("Authorization", "Bearer " + scoped))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TOKEN_SCOPE_DENIED"))
                .andExpect(jsonPath("$.category").value("AUTHORIZATION"))
                .andExpect(jsonPath("$.details.allowedCapabilities").exists())
                .andExpect(jsonPath("$.suggestedActions[0].action").value("REQUEST_SCOPED_TOKEN"));
    }

    @Test
    void unscopedTokenExercisesRuleManage() throws Exception {
        String full = issueToken("ai-full", null, "sdk", null);
        mockMvc.perform(get("/api/v1/apps/app-default/script-policy")
                        .header("Authorization", "Bearer " + full))
                .andExpect(status().isOk());
    }

    @Test
    void aiScopedTokenCanCreateAutomationSession() throws Exception {
        // An AI token scoped to RULE_MANAGE may create automation sessions.
        String ai = issueToken("ai-robot", "[\"RULE_MANAGE\"]", "mcp", null);
        mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("Authorization", "Bearer " + ai)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"caller\":\"ai-robot\",\"source\":\"mcp\",\"applicationId\":\"app-default\","
                                + "\"environmentId\":\"env-default\",\"requestedCapabilityProfile\":\"SAFE\",\"ttlMillis\":600000}"))
                .andExpect(status().isCreated());
    }

    @Test
    void concurrentSessionLimitEnforced() throws Exception {
        String limited = issueToken("ai-limited", null, "mcp", "1");
        String body = "{\"caller\":\"ai-limited\",\"source\":\"mcp\",\"applicationId\":\"app-default\","
                + "\"environmentId\":\"env-default\",\"requestedCapabilityProfile\":\"SAFE\",\"ttlMillis\":600000}";
        mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("Authorization", "Bearer " + limited)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Second concurrent session exceeds maxSessions=1.
        mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("Authorization", "Bearer " + limited)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTOMATION_SESSION_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.details.maxSessions").value(1));
    }
}
