package com.example.kairo.platform;

import com.example.kairo.api.operation.OperationStatus;
import com.example.kairo.api.operation.OperationType;
import com.example.kairo.api.write.RiskLevel;
import com.example.kairo.platform.operation.OperationService;
import com.example.kairo.platform.persistence.mapper.OperationMapper;
import com.example.kairo.platform.persistence.mapper.TestPlatformMapper;
import com.example.kairo.platform.service.PlatformException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V1.6 &sect;5.1 unified Operation: lifecycle, idempotency-key de-duplication,
 * optimistic locking, event stream and HTTP surface.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:kairo_platform_v16_op;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V16OperationIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired OperationService operationService;
    @Autowired OperationMapper operationMapper;
    @Autowired TestPlatformMapper fixtures;

    @BeforeEach
    void setUp() {
        fixtures.ensureDefaultProject();
        fixtures.ensureDefaultApplication();
        fixtures.ensureDefaultEnvironment();
    }

    @Test
    void operationLifecycleAndEvents() {
        String id = operationService.start(new OperationService.StartRequest(
                OperationType.AUTOMATION_TRIAL, "rule", "r-1", RiskLevel.MEDIUM,
                null, "alice", "corr-1", null, null));
        assertThat(operationService.get(id).status()).isEqualTo(OperationStatus.PENDING);
        assertThat(operationService.events(id)).hasSize(1);

        operationService.running(id);
        operationService.succeed(id, Map.of("ruleVersionId", "rv-1"));

        var op = operationService.get(id);
        assertThat(op.status()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(op.progress()).isEqualTo(100);
        assertThat(op.result()).containsEntry("ruleVersionId", "rv-1");
        assertThat(operationService.events(id)).hasSize(3); // CREATED, DISPATCHED, COMPLETED
    }

    @Test
    void idempotencyKeyDeduplicatesOperation() {
        var req = new OperationService.StartRequest(OperationType.PREVIEW, "rule", "r-2",
                RiskLevel.LOW, null, "alice", "corr", "idem-key-1", null);
        String first = operationService.start(req);
        String second = operationService.start(req);
        assertThat(second).isEqualTo(first);
    }

    @Test
    void optimisticLockRejectsStaleTransition() {
        String id = operationService.start(new OperationService.StartRequest(
                OperationType.AGENT_COMMAND, "rule", "r-3", RiskLevel.LOW,
                null, "alice", "corr", null, null));
        // Stale expected version (real version is 0) -> 0 rows updated.
        int updated = operationMapper.transition(id, OperationStatus.RUNNING.name(),
                -1, "{}", null, null, null,
                java.sql.Timestamp.from(java.time.Instant.now()), 99L);
        assertThat(updated).isZero();
    }

    @Test
    void getUnknownOperationReturns404WithV16ErrorContract() throws Exception {
        mockMvc.perform(get("/api/v1/operations/op-does-not-exist").header("X-Actor", "system"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.category").value("NOT_FOUND"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void httpSurfaceExposesOperationAndEvents() throws Exception {
        String id = operationService.start(new OperationService.StartRequest(
                OperationType.RULE_PUBLISH, "rule", "r-4", RiskLevel.HIGH,
                null, "alice", "corr", null, null));
        operationService.succeed(id, Map.of("ok", true));

        mockMvc.perform(get("/api/v1/operations/" + id).header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(id))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.type").value("RULE_PUBLISH"));

        mockMvc.perform(get("/api/v1/operations/" + id + "/events").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("CREATED"));

        mockMvc.perform(get("/api/v1/operations?status=SUCCEEDED").header("X-Actor", "system"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").exists());
    }

    @Test
    void unknownOperationEventsThrows404() {
        assertThatThrownBy(() -> operationService.events("op-missing"))
                .isInstanceOf(PlatformException.class)
                .satisfies(ex -> assertThat(((PlatformException) ex).code()).isEqualTo("RESOURCE_NOT_FOUND"));
    }
}
