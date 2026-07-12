package com.example.kairo.platform.script;

import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_script_web;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScriptSessionWebContractTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
    }

    @Test
    void getScriptPolicyReturnsDefaultsForAppWithoutPolicy() throws Exception {
        mockMvc.perform(get("/api/v1/apps/app-default/script-policy").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.platformMaxProfile").value("UNRESTRICTED"))
                .andExpect(jsonPath("$.applicationMaxProfile").value("UNRESTRICTED"))
                .andExpect(jsonPath("$.effectiveMaxProfile").value("UNRESTRICTED"))
                .andExpect(jsonPath("$.hasApplicationPolicy").value(false));
    }

    @Test
    void putScriptPolicyCreatesAndUpdatesWithOptimisticLock() throws Exception {
        mockMvc.perform(put("/api/v1/apps/app-default/script-policy")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedMaxProfile\":\"EXTENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.applicationMaxProfile").value("EXTENDED"));

        // Stale expectedRevision -> 409.
        mockMvc.perform(put("/api/v1/apps/app-default/script-policy")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedMaxProfile\":\"SAFE\",\"expectedRevision\":99}"))
                .andExpect(status().isConflict());
    }

    @Test
    void putScriptPolicyRejectsInvalidProfile() throws Exception {
        mockMvc.perform(put("/api/v1/apps/app-default/script-policy")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedMaxProfile\":\"OPEN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scriptEndpointsRequireRuleManageCapability() throws Exception {
        // "nobody" is neither a super-admin nor an active business user -> 403.
        mockMvc.perform(get("/api/v1/apps/app-default/script-policy").header("X-Actor", "nobody"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/script-sessions/anything").header("X-Actor", "nobody"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/scripts/compile")
                        .header("X-Actor", "nobody")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void describeUnknownSessionReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/script-sessions/no-such-session").header("X-Actor", "system"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteUnknownSessionReturns404() throws Exception {
        mockMvc.perform(delete("/api/v1/script-sessions/no-such-session").header("X-Actor", "system"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createSessionRejectsMissingRequiredFields() throws Exception {
        mockMvc.perform(post("/api/v1/script-sessions")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createSessionRejectsUnknownAgent() throws Exception {
        mockMvc.perform(post("/api/v1/script-sessions")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(content(Map.of(
                                "agentId", "agent-that-does-not-exist",
                                "target", Map.of("className", "com.example.Target",
                                        "classLoaderId", "loader-1", "methodName", "call",
                                        "methodDescriptor", "()V"),
                                "script", "return 1",
                                "capabilityProfile", "SAFE"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void compileRejectsMissingFields() throws Exception {
        mockMvc.perform(post("/api/v1/scripts/compile")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    private static String content(Map<String, Object> body) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
