package com.example.kairo.platform;

import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;5.3 server-side canonical rule preview/assembly contract: the platform
 * owns business defaults, returns a typed payload + preview token/revision +
 * impact/risk/revert metadata + script validation, and the returned payload
 * round-trips through the typed {@code POST /api/v1/rules} create endpoint.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_rule_preview;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RulePreviewIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TestPlatformMapper fixtures;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
    }

    @Test
    void previewReturnsCanonicalPayloadAndRoundTripsToCreate() throws Exception {
        String body = """
                {"name":"支付超时故障注入","applicationId":"app-default","environmentId":"env-dev",
                 "className":"com.example.PaymentService","methodName":"pay",
                 "classLoaderId":"bootstrap","methodDescriptor":"(I)I",
                 "executionPhase":"BEFORE","script":"return mock.proceed()"}
                """;
        MvcResult result = mockMvc.perform(post("/api/v1/rules/preview")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payload.name").value("支付超时故障注入"))
                .andExpect(jsonPath("$.payload.status").value("ENABLED"))
                .andExpect(jsonPath("$.payload.versionStatus").value("ENABLED"))
                .andExpect(jsonPath("$.payload.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.payload.targets[0].protocol").value("JAVA_METHOD"))
                .andExpect(jsonPath("$.payload.targets[0].className").value("com.example.PaymentService"))
                .andExpect(jsonPath("$.payload.targets[0].methodName").value("pay"))
                .andExpect(jsonPath("$.payload.targets[0].matcher.classLoaderId").value("bootstrap"))
                .andExpect(jsonPath("$.payload.targets[0].matcher.descriptor").value("(I)I"))
                .andExpect(jsonPath("$.payload.capabilities[0]").value("RETURN_VALUE"))
                .andExpect(jsonPath("$.payload.script.phase").value("BEFORE"))
                .andExpect(jsonPath("$.payload.script.script").value("return mock.proceed()"))
                .andExpect(jsonPath("$.payload.id").doesNotExist())
                .andExpect(jsonPath("$.previewToken").value(org.hamcrest.Matchers.startsWith("rule-prev-")))
                .andExpect(jsonPath("$.revision").exists())
                .andExpect(jsonPath("$.riskLevel").value("LOW"))
                .andExpect(jsonPath("$.impact.reversible").value(true))
                .andExpect(jsonPath("$.impact.estimatedAffectedInstances").value(1))
                .andExpect(jsonPath("$.validation.valid").value(true))
                .andExpect(jsonPath("$.revert.strategy").value("DISABLE_RULE_VERSION"))
                .andExpect(jsonPath("$.revert.steps[0]").exists())
                .andReturn();

        JsonNode response = mapper.readTree(result.getResponse().getContentAsString());
        JsonNode payload = response.get("payload");

        // Round-trip: forward the canonical payload verbatim to POST /api/v1/rules (typed DTO).
        mockMvc.perform(post("/api/v1/rules")
                        .header("X-Actor", "system")
                        .header("Idempotency-Key", "rule-preview-roundtrip-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload)))
                .andExpect(status().isCreated());
    }

    @Test
    void previewSurfacesStructuredDiagnosticsForBrokenScript() throws Exception {
        String body = """
                {"name":"broken","applicationId":"app-default","environmentId":"env-dev",
                 "className":"com.example.Service","methodName":"query",
                 "classLoaderId":"bootstrap","methodDescriptor":"()V",
                 "executionPhase":"RETURN","script":"def x ="}
                """;
        mockMvc.perform(post("/api/v1/rules/preview")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.validation.valid").value(false))
                .andExpect(jsonPath("$.validation.diagnostics[0].code").exists())
                .andExpect(jsonPath("$.validation.diagnostics[0].severity").value("error"))
                .andExpect(jsonPath("$.riskLevel").value("LOW"));
    }

    @Test
    void previewRejectsMissingRequiredFieldWithStructuredError() throws Exception {
        // executionPhase missing -> structured ApiError (code-based), not a 500.
        mockMvc.perform(post("/api/v1/rules/preview")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"x","applicationId":"app-default","environmentId":"env-dev",
                                 "className":"com.example.Service","methodName":"query",
                                 "classLoaderId":"bootstrap","methodDescriptor":"()V",
                                 "script":"return mock.proceed()"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIELD_REQUIRED"))
                .andExpect(jsonPath("$.category").value("VALIDATION"))
                .andExpect(jsonPath("$.correlationId").exists());
    }
}
