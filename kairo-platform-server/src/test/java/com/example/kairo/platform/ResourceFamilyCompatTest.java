package com.example.kairo.platform;

import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;3 / &sect;6 exhaustive resource-family coverage: every deprecated
 * {@code /query/{resource}} family remains backward compatible and is marked
 * deprecated in OpenAPI, and every first-class resource-family endpoint is
 * exposed and publishes camelCase JSON (no snake_case leak).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_resource;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResourceFamilyCompatTest {

    /** Every resource the deprecated generic query endpoint exposes (PlatformQueryService.RESOURCES). */
    private static final List<String> DEPRECATED_RESOURCES = List.of(
            "applications", "rollout-environments", "rollout-applications", "environments",
            "instances", "sidecars", "attach-executors", "attach-targets",
            "attach-executor-commands", "agents", "agent-commands", "rules",
            "rule-versions", "operation-plans", "rollout-executions", "rollout-targets",
            "rollback-executions", "tokens");

    /**
     * New V1.6 §3 first-class resource-family endpoints (ResourceFamilyController).
     * These publish camelCase JSON; the legacy PlatformController list GETs are retained
     * with their existing shape for backward compatibility (§3 gradual migration).
     */
    private static final List<String> CAMEL_CASE_ENDPOINTS = List.of(
            "/api/v1/applications", "/api/v1/environments", "/api/v1/audit-events",
            "/api/v1/diagnostics", "/api/v1/rollout-environments", "/api/v1/rollout-applications",
            "/api/v1/rollout-targets", "/api/v1/rollback-executions", "/api/v1/attach-executors",
            "/api/v1/attach-targets", "/api/v1/attach-executor-commands");

    /** Legacy first-class list endpoints retained from V1.1–V1.5 (PlatformController GETs). */
    private static final List<String> LEGACY_LIST_ENDPOINTS = List.of(
            "/api/v1/instances", "/api/v1/agents", "/api/v1/rules", "/api/v1/rule-versions",
            "/api/v1/sidecars", "/api/v1/fencing-tokens", "/api/v1/agent-commands",
            "/api/v1/operation-plans", "/api/v1/rollout-executions");

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
    void exhaustiveDeprecatedQueryResourcesRemainBackwardCompatible() throws Exception {
        for (String resource : DEPRECATED_RESOURCES) {
            mockMvc.perform(get("/api/v1/query/" + resource + "?page=0&size=1").header("X-Actor", "system"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items").exists());
        }
    }

    @Test
    void openApiMarksDeprecatedPaths() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/query/{resource}'].get.deprecated").value(true))
                .andExpect(jsonPath("$.paths['/api/v1/details/{resource}/{id}'].get.deprecated").value(true));
    }

    @Test
    void firstClassResourceFamilyEndpointsAreExposedAndCamelCase() throws Exception {
        for (String endpoint : CAMEL_CASE_ENDPOINTS) {
            MvcResult result = mockMvc.perform(get(endpoint).header("X-Actor", "system"))
                    .andExpect(status().isOk())
                    .andReturn();
            assertNoSnakeCase(mapper.readTree(result.getResponse().getContentAsString()));
        }
    }

    @Test
    void legacyFirstClassListEndpointsRemainAvailable() throws Exception {
        // Legacy list GETs are retained with their existing shape (§3 gradual migration).
        for (String endpoint : LEGACY_LIST_ENDPOINTS) {
            mockMvc.perform(get(endpoint).header("X-Actor", "system"))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void firstClassReplacementsAreEquivalentToDeprecatedQuery() throws Exception {
        // The first-class /applications endpoint returns the same rows the deprecated
        // /query/applications does (same count), proving it is a true replacement.
        int firstClass = readList("/api/v1/applications").size();
        int deprecated = mapper.readTree(mockMvc.perform(get("/api/v1/query/applications?page=0&size=200&q=")
                                .header("X-Actor", "system"))
                        .andReturn().getResponse().getContentAsString()).get("items").size();
        org.assertj.core.api.Assertions.assertThat(firstClass).isEqualTo(deprecated).isPositive();
    }

    private com.fasterxml.jackson.databind.node.ArrayNode readList(String path) throws Exception {
        return (com.fasterxml.jackson.databind.node.ArrayNode)
                mapper.readTree(mockMvc.perform(get(path).header("X-Actor", "system"))
                        .andReturn().getResponse().getContentAsString());
    }

    /** Recursively assert no JSON key contains a snake_case underscore (§2.2 camelCase contract). */
    private void assertNoSnakeCase(JsonNode node) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                org.assertj.core.api.Assertions.assertThat(entry.getKey())
                        .as("resource-family JSON must be camelCase, found snake_case key")
                        .doesNotContain("_");
                assertNoSnakeCase(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::assertNoSnakeCase);
        }
    }
}
