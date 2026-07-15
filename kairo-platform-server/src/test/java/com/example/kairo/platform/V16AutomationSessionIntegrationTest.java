package com.example.kairo.platform;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.automation.AutomationSessionStatus;
import com.example.kairo.platform.automation.AutomationSessionService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;4 AutomationSession: create, profile narrowing, one-click revert,
 * TTL expiry and the task-level HTTP surface.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_auto;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V16AutomationSessionIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired AutomationSessionService sessionService;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestPlatformMapper fixtures;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
    }

    @Test
    void createGetAndRevertSession() throws Exception {
        String body = """
                {"caller":"ai-bot","source":"mcp","applicationId":"app-default",
                 "environmentId":"env-default","requestedCapabilityProfile":"SAFE","ttlMillis":600000}
                """;
        String id = mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.maxCapabilityProfile").value("SAFE"))
                .andReturn().getResponse().getContentAsString();
        // extract sessionId via JSON; use Jackson-free simple parse
        String sessionId = id.split("\"sessionId\":\"")[1].split("\"")[0];

        mockMvc.perform(get("/api/v1/automation-sessions/" + sessionId).header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(sessionId));

        mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/revert").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERTED"))
                .andExpect(jsonPath("$.cleanupResult.revertedCount").exists());

        // Revert is idempotent.
        mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/revert").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERTED"));
    }

    @Test
    void profileNarrowsToApplicationPolicy() throws Exception {
        // Pin the application policy to SAFE.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/apps/app-default/script-policy")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allowedMaxProfile\":\"SAFE\"}"))
                .andExpect(status().isOk());

        // Request UNRESTRICTED; the session must narrow to SAFE.
        String body = """
                {"caller":"ai-bot","source":"sdk","applicationId":"app-default",
                 "environmentId":"env-default","requestedCapabilityProfile":"UNRESTRICTED","ttlMillis":600000}
                """;
        mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.maxCapabilityProfile").value("SAFE"));
    }

    @Test
    void ttlExpiryRevertsAndExpiresSession() {
        var session = sessionService.create(
                new com.example.kairo.platform.service.RequestContext("system", "corr", "127.0.0.1", "header-dev", "test"),
                new AutomationSessionService.CreateRequest("ai-bot", "mcp", "app-default",
                        "env-default", null, null, CapabilityProfile.SAFE, 600_000L));
        // Force the deadline into the past.
        jdbc.update("update automation_session set deadline_millis = 1 where id = ?", session.sessionId());

        var result = sessionService.expireSessions();
        assertThat(result.get("expired")).as("expected at least one expired session").isNotEqualTo(0);

        var after = sessionService.get(new com.example.kairo.platform.service.RequestContext(
                "system", "corr", "127.0.0.1", "header-dev", "test"), session.sessionId());
        assertThat(after.status()).isEqualTo(AutomationSessionStatus.EXPIRED);
    }

    @Test
    void revertOnExpiredSessionReturns409() throws Exception {
        var session = sessionService.create(
                new com.example.kairo.platform.service.RequestContext("system", "corr", "127.0.0.1", "header-dev", "test"),
                new AutomationSessionService.CreateRequest("ai-bot", "mcp", "app-default",
                        "env-default", null, null, CapabilityProfile.SAFE, 600_000L));
        jdbc.update("update automation_session set deadline_millis = 1 where id = ?", session.sessionId());
        sessionService.expireSessions();

        mockMvc.perform(post("/api/v1/automation-sessions/" + session.sessionId() + "/revert")
                        .header("X-Actor", "system"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTOMATION_SESSION_TERMINAL"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void eventsStreamIsExposed() throws Exception {
        var session = sessionService.create(
                new com.example.kairo.platform.service.RequestContext("system", "corr", "127.0.0.1", "header-dev", "test"),
                new AutomationSessionService.CreateRequest("ai-bot", "mcp", "app-default",
                        "env-default", null, null, CapabilityProfile.SAFE, 600_000L));
        mockMvc.perform(get("/api/v1/automation-sessions/" + session.sessionId() + "/events")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("lifecycle"))
                .andExpect(jsonPath("$[0].eventType").value("SESSION_CREATED"));
    }
}
