package com.example.runtimemock.control;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneServiceTest {

    private final ControlPlaneService service = new ControlPlaneService(
            Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void createsRecordingDatasetReplayAndAuditChain() {
        RecordingSession session = service.createRecordingSession(Map.of(
                "id", "rec-1",
                "application", "order-service",
                "environment", "prod",
                "actor", "alice"
        ));

        assertThat(session.status()).isEqualTo(RecordingSessionStatus.DRAFT);
        assertThat(session.version()).isEqualTo(1);

        service.transitionRecordingSession("rec-1", transition("DRAFT", 1, "WAITING_APPROVAL"));
        service.transitionRecordingSession("rec-1", transition("WAITING_APPROVAL", 2, "APPROVED"));
        service.transitionRecordingSession("rec-1", transition("APPROVED", 3, "RECORDING"));
        RecordingSession completed = service.transitionRecordingSession(
                "rec-1", transition("RECORDING", 4, "COMPLETED"));

        assertThat(completed.status()).isEqualTo(RecordingSessionStatus.COMPLETED);
        assertThat(completed.version()).isEqualTo(5);

        DatasetVersion dataset = service.createDatasetVersion(Map.of(
                "datasetId", "dataset-orders",
                "sourceSessionId", "rec-1",
                "schemaHash", "schema-sha256",
                "manifestHash", "manifest-sha256",
                "maskingHash", "masking-sha256",
                "actor", "alice"
        ));

        assertThat(dataset.version()).isEqualTo(1);
        assertThat(dataset.id()).isEqualTo("dataset-orders:1");

        ReplayPlan plan = service.createReplayPlan(Map.of(
                "id", "replay-1",
                "datasetId", "dataset-orders",
                "datasetVersion", 1,
                "targetEnvironment", "test",
                "targetApplication", "order-service",
                "sideEffectPolicyHash", "side-effect-sha256",
                "comparisonPolicyHash", "comparison-sha256",
                "actor", "bob"
        ));

        assertThat(plan.status()).isEqualTo(PlanStatus.DRAFT);
        ReplayPlan waitingApproval = service.transitionReplayPlan(
                "replay-1", replayTransition("DRAFT", 1, "WAITING_APPROVAL"));
        assertThat(waitingApproval.status()).isEqualTo(PlanStatus.WAITING_APPROVAL);

        assertThat(service.audits()).hasSize(8);
        assertThat(service.audits().get(0).previousRecordHash()).isEqualTo("GENESIS");
        assertThat(service.audits().get(1).previousRecordHash()).isEqualTo(service.audits().get(0).recordHash());
    }

    @Test
    void rejectsDatasetCreationBeforeRecordingSessionCompleted() {
        service.createRecordingSession(Map.of(
                "id", "rec-2",
                "application", "order-service",
                "environment", "prod"
        ));

        assertThatThrownBy(() -> service.createDatasetVersion(Map.of(
                "datasetId", "dataset-orders",
                "sourceSessionId", "rec-2",
                "schemaHash", "schema-sha256",
                "manifestHash", "manifest-sha256",
                "maskingHash", "masking-sha256"
        )))
                .isInstanceOf(ControlPlaneException.class)
                .hasMessageContaining("completed recording session");
    }

    @Test
    void rejectsStaleExpectedStatusAndVersion() {
        service.createRecordingSession(Map.of(
                "id", "rec-3",
                "application", "order-service",
                "environment", "prod"
        ));

        assertThatThrownBy(() -> service.transitionRecordingSession(
                "rec-3", transition("WAITING_APPROVAL", 2, "APPROVED")))
                .isInstanceOf(ControlPlaneException.class)
                .hasMessageContaining("status or version has changed");
    }

    private Map<String, Object> transition(String expectedStatus, long expectedVersion, String targetStatus) {
        return Map.of(
                "expectedStatus", expectedStatus,
                "expectedVersion", expectedVersion,
                "targetStatus", targetStatus,
                "actor", "alice",
                "reason", "test transition",
                "fencingToken", "token-" + expectedVersion
        );
    }

    private Map<String, Object> replayTransition(String expectedStatus, long expectedVersion, String targetStatus) {
        return Map.of(
                "expectedStatus", expectedStatus,
                "expectedVersion", expectedVersion,
                "targetStatus", targetStatus,
                "actor", "bob",
                "reason", "test replay transition",
                "fencingToken", "replay-token-" + expectedVersion
        );
    }
}
