package com.example.kairo.platform.freeze;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.7 M0 / &sect;3.1: the public API V1 freeze gate. Compare-only. Fetches the live
 * {@code /v3/api-docs}, normalizes it semantically (operations with parameters' schema/ref/type/
 * format/enum/nullable, requestBody required/schema, response status/schema; component schemas with
 * type/format/enum/nullable/required/items/additionalProperties/composition; security schemes) and
 * asserts the live contract is backward-compatible with the V1.6.0 baseline ({@code 113823b}):
 * removals and narrowing fail the build; genuinely additive changes are allowed.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v17_freeze;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiV1FreezeTest {

    private static final String BASELINE = "v1.7/api-v1-openapi-normalized.json";

    @Autowired MockMvc mockMvc;

    @Test
    void frozenApiV1IsBackwardCompatible() throws Exception {
        FreezeModels.NormalizedApi current = normalizeLive();
        FreezeModels.NormalizedApi baseline =
                FreezeBaselineSupport.readBaseline(BASELINE, FreezeModels.NormalizedApi.class);

        List<String> violations = new ArrayList<>(
                ApiCompatibilityComparator.compare(baseline, current));

        if (!violations.isEmpty()) {
            System.err.println("[freeze] BREAKING API V1 CHANGES:");
            violations.forEach(v -> System.err.println("  - " + v));
        }
        assertThat(violations)
                .as("Frozen public API V1 (V1.6.0 / 113823b) must not have removals or narrowing.")
                .isEmpty();
    }

    private FreezeModels.NormalizedApi normalizeLive() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        JsonNode doc = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        return FreezeCollectors.normalizeOpenApi(doc);
    }
}
