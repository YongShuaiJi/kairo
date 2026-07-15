package com.example.kairo.platform;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.automation.AutomationSessionStatus;
import com.example.kairo.api.write.RiskLevel;
import com.example.kairo.platform.automation.AutomationSessionService;
import com.example.kairo.platform.persistence.mapper.AutomationSessionMapper;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;8 / &sect;9.8: complete idempotency and optimistic-concurrency coverage
 * for the V1.6 write APIs. Idempotency is enforced cross-cuttingly by {@link
 * com.example.kairo.platform.api.IdempotencyFilter} (body hash + actor + method +
 * uri + query); these tests prove it engages on the V1.6 paths (replay returns the
 * cached response; a different body under the same key returns
 * {@code IDEMPOTENCY_KEY_CONFLICT}). Optimistic locking is proven at the data layer
 * (a stale {@code expectedVersion} updates zero rows), mirroring the existing
 * Operation / script-session / script-policy / operation-plan coverage.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_write_safety;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V16WriteApiIdempotencyOptimisticLockTest {

    @Autowired MockMvc mockMvc;
    @Autowired AutomationSessionService sessionService;
    @Autowired AutomationSessionMapper sessionMapper;
    @Autowired TestPlatformMapper fixtures;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final RequestContext CTX =
            new RequestContext("system", "corr-write", "127.0.0.1", "header-dev", "test");

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
    }

    private String createSessionBody(String caller) {
        return """
                {"caller":"%s","source":"mcp","applicationId":"app-default",
                 "environmentId":"env-default","requestedCapabilityProfile":"SAFE","ttlMillis":600000}
                """.formatted(caller);
    }

    @Test
    void idempotencyReplaysCreateAutomationSession() throws Exception {
        String key = "idem-auto-create-" + System.nanoTime();
        MvcResult first = mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createSessionBody("ai-bot")))
                .andExpect(status().isCreated())
                .andReturn();
        String firstId = mapper.readTree(first.getResponse().getContentAsString()).get("sessionId").asText();

        // Replay with the same key + body -> the cached 201 with the same sessionId.
        MvcResult replay = mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createSessionBody("ai-bot")))
                .andExpect(status().isCreated())
                .andReturn();
        String replayId = mapper.readTree(replay.getResponse().getContentAsString()).get("sessionId").asText();
        assertThat(replayId).isEqualTo(firstId);
    }

    @Test
    void idempotencyConflictsOnDifferentBodySameKey() throws Exception {
        String key = "idem-auto-conflict-" + System.nanoTime();
        mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createSessionBody("ai-bot")))
                .andExpect(status().isCreated());

        // Same key, different body (different caller) -> 409 IDEMPOTENCY_KEY_CONFLICT.
        mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createSessionBody("ai-other")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"))
                .andExpect(jsonPath("$.category").value("CONFLICT"));
    }

    @Test
    void idempotencyReplaysRulePreview() throws Exception {
        String key = "idem-rule-preview-" + System.nanoTime();
        String body = """
                {"name":"preview-idem","applicationId":"app-default","environmentId":"env-dev",
                 "className":"com.example.Pay","methodName":"pay","classLoaderId":"bootstrap",
                 "methodDescriptor":"()V","executionPhase":"BEFORE","script":"return mock.proceed()"}
                """;
        MvcResult first = mockMvc.perform(post("/api/v1/rules/preview")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode firstJson = mapper.readTree(first.getResponse().getContentAsString());

        MvcResult replay = mockMvc.perform(post("/api/v1/rules/preview")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode replayJson = mapper.readTree(replay.getResponse().getContentAsString());
        // The cached response carries the same preview token/revision (not a fresh one).
        assertThat(replayJson.get("previewToken").asText()).isEqualTo(firstJson.get("previewToken").asText());
        assertThat(replayJson.get("revision").asLong()).isEqualTo(firstJson.get("revision").asLong());
    }

    @Test
    void idempotencyReplaysValidateScript() throws Exception {
        // Create a session without an idempotency key, then validate-script with one.
        String sessionId = mapper.readTree(mockMvc.perform(post("/api/v1/automation-sessions")
                                .header("X-Actor", "system")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createSessionBody("ai-bot")))
                        .andExpect(status().isCreated())
                        .andReturn().getResponse().getContentAsString()).get("sessionId").asText();

        String key = "idem-validate-" + System.nanoTime();
        String body = "{\"script\":\"def x =\"}";
        MvcResult first = mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/validate-script")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andReturn();
        String firstDiagnostics = mapper.readTree(first.getResponse().getContentAsString())
                .get("diagnostics").toString();

        MvcResult replay = mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/validate-script")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        String replayDiagnostics = mapper.readTree(replay.getResponse().getContentAsString())
                .get("diagnostics").toString();
        assertThat(replayDiagnostics).isEqualTo(firstDiagnostics);
    }

    @Test
    void automationSessionOptimisticLockRejectsStaleVersion() {
        var session = sessionService.create(CTX, new AutomationSessionService.CreateRequest(
                "ai-bot", "mcp", "app-default", "env-default", null, null,
                CapabilityProfile.SAFE, 600_000L));
        assertThat(session.status()).isEqualTo(AutomationSessionStatus.CREATED);
        // Real version is 0; a stale expectedVersion updates zero rows (the optimistic lock).
        int updated = sessionMapper.transition(session.sessionId(),
                AutomationSessionStatus.REVERTED.name(), RiskLevel.LOW.name(), "{}",
                Timestamp.from(Instant.now()), 99L);
        assertThat(updated)
                .as("stale expectedVersion must not transition the session")
                .isZero();
        // The session is still CREATED (the transition was rejected).
        assertThat(sessionService.get(CTX, session.sessionId()).status())
                .isEqualTo(AutomationSessionStatus.CREATED);
    }
}
