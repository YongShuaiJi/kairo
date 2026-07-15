package com.example.kairo.platform;

import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;3 /rule-chains + /reconciliations resources: 404 for unknown chains,
 * 400 for missing fields, and structured errors per the V1.6 error model.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_rulechain;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RuleChainControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired TestPlatformMapper fixtures;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
    }

    @Test
    void unknownRuleChainReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/rule-chains")
                        .param("applicationId", "app-default")
                        .param("environmentId", "env-default")
                        .param("agentId", "agent-missing")
                        .param("chainId", "chain-missing")
                        .header("X-Actor", "system"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"));
    }

    @Test
    void reconciliationRejectsMissingFields() throws Exception {
        mockMvc.perform(post("/api/v1/reconciliations")
                        .header("X-Actor", "system")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicationId\":\"app-default\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_FIELD"))
                .andExpect(jsonPath("$.category").value("VALIDATION"));
    }
}
