package com.example.kairo.platform;

import com.example.kairo.platform.api.KairoApiAuthorizationCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;6 / &sect;9 OpenAPI contract tests: the generated document covers the
 * public V1 paths, the frozen error schema, and a breaking-change guard over the
 * committed operation set.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_openapi;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiContractTest {

    @Autowired MockMvc mockMvc;

    private String openApiDoc() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString();
    }

    @Test
    void openApiDocumentIsExposedWithoutAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Kairo Platform API"))
                .andExpect(jsonPath("$.info.version").value("1.6"));
    }

    @Test
    void v16AiFirstPathsArePresent() throws Exception {
        String doc = openApiDoc();
        // Breaking-change guard: every committed V1.6 operation must remain in the document.
        for (String path : new String[]{
                "/api/v1/automation-sessions",
                "/api/v1/automation-sessions/{id}",
                "/api/v1/automation-sessions/{id}/resolve-targets",
                "/api/v1/automation-sessions/{id}/preview",
                "/api/v1/automation-sessions/{id}/trial",
                "/api/v1/automation-sessions/{id}/promote",
                "/api/v1/automation-sessions/{id}/revert",
                "/api/v1/automation-sessions/{id}/events",
                "/api/v1/operations",
                "/api/v1/operations/{id}",
                "/api/v1/operations/{id}/events",
                "/api/v1/rule-chains",
                "/api/v1/reconciliations",
                "/api/v1/rules/preview",
                "/api/v1/schemas"
        }) {
            org.assertj.core.api.Assertions.assertThat(doc)
                    .as("OpenAPI document must contain path %s", path)
                    .contains("\"" + path + "\"");
        }
    }

    @Test
    void errorSchemaCarriesFullV16Contract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.code").exists())
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.category").exists())
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.retryable").exists())
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.suggestedActions").exists())
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.correlationId").exists())
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.field").exists())
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.path").exists())
                .andExpect(jsonPath("$.components.schemas.ApiError.properties.location").exists());
    }

    @Test
    void schemasEndpointReturnsMachineReadableBundle() throws Exception {
        mockMvc.perform(get("/api/v1/schemas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errorModel.properties.code").exists())
                .andExpect(jsonPath("$.errorModel.properties.suggestedActions").exists())
                .andExpect(jsonPath("$.scriptApiSurface").exists())
                .andExpect(jsonPath("$.operationStatus.enum[0]").value("PENDING"))
                .andExpect(jsonPath("$.openapiDocument").value("/v3/api-docs"));
    }

    @Test
    void coreWritePathPublishesTypedRequestSchema() throws Exception {
        // V1.6 §2.2/§5.1: core write paths publish explicit request DTOs (no free-form Map).
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CreateInstanceRequest.properties.applicationId").exists())
                .andExpect(jsonPath("$.components.schemas.CreateInstanceRequest.properties.hostname").exists())
                .andExpect(jsonPath("$.components.schemas.IssueFencingTokenRequest.properties.resourceType").exists())
                .andExpect(jsonPath("$.components.schemas.CreateSidecarRequest.properties.endpoint").exists())
                .andExpect(jsonPath("$.components.schemas.CreateAgentRequest.properties.listenHost").exists())
                .andExpect(jsonPath("$.components.schemas.CreateOperationPlanRequest.properties.resourceType").exists())
                // V1.6 rule/rule-version payloads are fully typed (targets/script/matcher),
                // not free-form Maps (§2.2).
                .andExpect(jsonPath("$.components.schemas.CreateRuleRequest.properties.name").exists())
                .andExpect(jsonPath("$.components.schemas.CreateRuleRequest.properties.targets").exists())
                .andExpect(jsonPath("$.components.schemas.CreateRuleRequest.properties.script").exists())
                .andExpect(jsonPath("$.components.schemas.CreateRuleRequest.properties.matcher").exists())
                .andExpect(jsonPath("$.components.schemas.CreateRuleVersionRequest.properties.targets").exists())
                .andExpect(jsonPath("$.components.schemas.CreateRuleVersionRequest.properties.script").exists())
                .andExpect(jsonPath("$.components.schemas.RuleTargetDto.properties.protocol").exists())
                .andExpect(jsonPath("$.components.schemas.RuleTargetDto.properties.className").exists())
                .andExpect(jsonPath("$.components.schemas.RuleTargetMatcherDto.properties.classLoaderId").exists())
                .andExpect(jsonPath("$.components.schemas.RuleTargetMatcherDto.properties.descriptor").exists())
                .andExpect(jsonPath("$.components.schemas.RuleScriptDto.properties.phase").exists())
                .andExpect(jsonPath("$.components.schemas.RuleScriptDto.properties.script").exists())
                .andExpect(jsonPath("$.components.schemas.RulePreviewRequest.properties.executionPhase").exists())
                .andExpect(jsonPath("$.components.schemas.RulePreviewResponse.properties.payload").exists())
                .andExpect(jsonPath("$.components.schemas.RulePreviewResponse.properties.previewToken").exists())
                .andExpect(jsonPath("$.components.schemas.RulePreviewResponse.properties.riskLevel").exists())
                .andExpect(jsonPath("$.components.schemas.RulePreviewResponse.properties.revert").exists());
    }

    @Test
    void everyOperationPublishesItsFrozenAuthorizationExpression() throws Exception {
        JsonNode document = new ObjectMapper().readTree(openApiDoc());
        java.util.Set<KairoApiAuthorizationCatalog.Route> live = new java.util.LinkedHashSet<>();
        java.util.Set<String> methods = java.util.Set.of(
                "get", "post", "put", "patch", "delete", "head", "options");
        document.path("paths").fields().forEachRemaining(pathEntry ->
                pathEntry.getValue().fields().forEachRemaining(methodEntry -> {
                    if (!methods.contains(methodEntry.getKey())) {
                        return;
                    }
                    KairoApiAuthorizationCatalog.Route route =
                            new KairoApiAuthorizationCatalog.Route(
                                    methodEntry.getKey(), pathEntry.getKey());
                    live.add(route);
                    org.assertj.core.api.Assertions.assertThat(methodEntry.getValue()
                                    .path(KairoApiAuthorizationCatalog.EXTENSION).asText())
                            .as("authorization extension for " + route)
                            .isEqualTo(KairoApiAuthorizationCatalog.requirement(
                                    route.method(), route.path()));
                }));

        org.assertj.core.api.Assertions.assertThat(live)
                .as("every explicit authorization override must resolve to a live operation")
                .containsAll(KairoApiAuthorizationCatalog.overrides().keySet());
        String expressions = String.join("\n", KairoApiAuthorizationCatalog.declaredExpressions());
        org.assertj.core.api.Assertions.assertThat(expressions)
                .contains("ADMIN", "USER_MANAGE", "INSTANCE_MANAGE", "AGENT_MANAGE",
                        "RULE_MANAGE", "ROLLOUT_MANAGE");
    }
}
