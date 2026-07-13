package com.example.kairo.platform.command;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1.5 &sect;4.4/§5: an ACKED APPLY_RULE/APPLY_CHAIN whose inner ApplyChainStatus is a real
 * apply failure (notably {@code TARGET_DRIFTED} after a hot update) must surface as a
 * rollout failure, not a silent success. This tests the status-classification logic the
 * ack path uses to compute rollout success.
 */
class AgentCommandChainFailureStatusTest {

    @Test
    void driftedAndOtherFailuresAreChainFailures() {
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "TARGET_DRIFTED"))).isTrue();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "COMPILE_FAILED"))).isTrue();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "TRANSFORM_FAILED"))).isTrue();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "VERIFICATION_FAILED"))).isTrue();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "COEXISTENCE_UNSAFE"))).isTrue();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "TARGET_NOT_FOUND"))).isTrue();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "REJECTED"))).isTrue();
    }

    @Test
    void appliedNoOpAndDegradedAreNotChainFailures() {
        // APPLIED / NO_OP / IDEMPOTENT_REPLAY / STALE_COMMAND are success-ish; DEGRADED is a
        // partial success (the rule applied, in degraded mode) so the rollout still advances.
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "APPLIED"))).isFalse();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "NO_OP"))).isFalse();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "IDEMPOTENT_REPLAY"))).isFalse();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "STALE_COMMAND"))).isFalse();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", "DEGRADED"))).isFalse();
    }

    @Test
    void nullResultAndMissingStatusAreNotFailures() {
        assertThat(AgentCommandService.isChainFailureStatus(null)).isFalse();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of())).isFalse();
        assertThat(AgentCommandService.isChainFailureStatus(Map.of("status", ""))).isFalse();
    }
}
