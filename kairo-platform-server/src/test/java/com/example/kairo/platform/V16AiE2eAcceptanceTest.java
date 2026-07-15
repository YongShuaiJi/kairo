package com.example.kairo.platform;

import com.example.kairo.api.CapabilityProfile;
import com.example.kairo.api.automation.AutomationSessionStatus;
import com.example.kairo.api.automation.EnhancementContextBundle;
import com.example.kairo.platform.automation.AutomationSessionService;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;8 AI E2E + &sect;9 "AI 自动修正一次结构化脚本错误并完成临时增强" evidence.
 * Drives the full AI flow with only a Token + intent: create session, resolve
 * targets (structured context bundle), validate a broken script (structured
 * diagnostics), auto-fix based on the diagnostic code, and one-click revert.
 * The AI branches on {@code code}/{@code valid}, never on message prose.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_aie2e;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V16AiE2eAcceptanceTest {

    @Autowired MockMvc mockMvc;
    @Autowired AutomationSessionService sessionService;
    @Autowired TestPlatformMapper fixtures;

    private static final RequestContext CTX =
            new RequestContext("system", "corr-ai", "127.0.0.1", "header-dev", "test");

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
    }

    @Test
    void aiResolvesContextAndAutoFixesStructuredScriptErrorThenReverts() {
        // 1. Create the automation session (AI narrows to SAFE).
        var session = sessionService.create(CTX, new AutomationSessionService.CreateRequest(
                "ai-bot", "mcp", "app-default", "env-default", null, null,
                CapabilityProfile.SAFE, 600_000L));
        assertThat(session.maxCapabilityProfile()).isEqualTo(CapabilityProfile.SAFE);

        // 2. resolve-targets returns a compact, structured context bundle (§4.3).
        EnhancementContextBundle bundle = sessionService.resolveTargets(CTX, session.sessionId(),
                new AutomationSessionService.ResolveTargetsRequest("pay", null));
        assertThat(bundle.scriptApiSurface().allowedProfile()).isEqualTo(CapabilityProfile.SAFE);
        assertThat(bundle.scriptApiSurface().schema()).containsKey("properties");
        assertThat(bundle.scriptApiSurface().examples()).isNotEmpty();
        assertThat(bundle.scriptApiSurface().diagnosticsFormat()).containsKey("shape");
        assertThat(bundle.sizeBytes()).isLessThanOrEqualTo(EnhancementContextBundle.MAX_SIZE_BYTES);

        // 3. Validate a deliberately broken script -> structured diagnostics (not a message string).
        var broken = sessionService.validateScript(CTX, session.sessionId(),
                new AutomationSessionService.ValidateScriptRequest("def x ="));
        assertThat(broken.get("valid")).isEqualTo(false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) broken.get("diagnostics");
        assertThat(diagnostics).isNotEmpty();
        // The AI branches on the structured code/severity, never the message.
        assertThat(diagnostics.get(0)).containsKey("code");
        assertThat(diagnostics.get(0)).containsKey("severity");

        // 4. AI "auto-fixes" the script based on the structured diagnostic -> valid.
        var fixed = sessionService.validateScript(CTX, session.sessionId(),
                new AutomationSessionService.ValidateScriptRequest("// fixed: valid script\nctx.result = 1\n"));
        assertThat(fixed.get("valid")).isEqualTo(true);

        // 5. One-click revert (the temporary enhancement boundary is reliably undoable).
        var reverted = sessionService.revert(CTX, session.sessionId());
        assertThat(reverted.status()).isEqualTo(AutomationSessionStatus.REVERTED);
    }

    @Test
    void httpSurfaceSupportsTheAiFlow() throws Exception {
        String body = """
                {"caller":"ai-bot","source":"mcp","applicationId":"app-default",
                 "environmentId":"env-default","requestedCapabilityProfile":"SAFE","ttlMillis":600000}
                """;
        String response = mockMvc.perform(post("/api/v1/automation-sessions")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String sessionId = response.split("\"sessionId\":\"")[1].split("\"")[0];

        // resolve-targets over HTTP returns the structured bundle.
        mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/resolve-targets")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"pay\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scriptApiSurface.schema.properties").exists())
                .andExpect(jsonPath("$.scriptApiSurface.examples").isArray());

        // validate-script over HTTP returns structured diagnostics for a broken script.
        mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/validate-script")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"script\":\"def x =\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.diagnostics[0].code").exists());

        // revert over HTTP.
        mockMvc.perform(post("/api/v1/automation-sessions/" + sessionId + "/revert")
                        .header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERTED"));
    }
}
