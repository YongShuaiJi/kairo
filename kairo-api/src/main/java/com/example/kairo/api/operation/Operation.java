package com.example.kairo.api.operation;

import com.example.kairo.api.error.ApiError;
import com.example.kairo.api.write.ImpactSummary;
import com.example.kairo.api.write.RiskLevel;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.Objects;

/**
 * Unified long-running operation resource (V1.6 &sect;2.2 "长操作返回 operationId，
 * 使用统一 Operation 资源查询" / &sect;5.1). Every multi-step or asynchronous write
 * (agent command, publish, rollback, preview, automation trial/revert) is surfaced
 * through this single queryable resource.
 *
 * @param operationId       stable id, also returned in the {@code operationId} field of write responses
 * @param type              {@link OperationType}
 * @param status            {@link OperationStatus}
 * @param resourceType      primary affected resource type
 * @param resourceId        primary affected resource id
 * @param riskLevel         machine-readable risk
 * @param impact            structured impact range
 * @param progress          0..100, or -1 when unknown
 * @param result            structured result on success
 * @param error             structured error on failure
 * @param revertOperationId id of a revert Operation, providing the "撤销链接" (V1.6 &sect;2.3)
 * @param correlationId     links to audit/log
 * @param actor             who initiated the operation
 * @param createdAt         epoch millis
 * @param updatedAt         epoch millis
 * @param completedAt       epoch millis, or -1 when not completed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Operation(
        String operationId,
        OperationType type,
        OperationStatus status,
        String resourceType,
        String resourceId,
        RiskLevel riskLevel,
        ImpactSummary impact,
        int progress,
        Map<String, Object> result,
        ApiError error,
        String revertOperationId,
        String correlationId,
        String actor,
        long createdAt,
        long updatedAt,
        long completedAt
) {
    public Operation {
        operationId = requireText(operationId, "operationId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        resourceType = resourceType == null ? "" : resourceType;
        resourceId = resourceId == null ? "" : resourceId;
        riskLevel = riskLevel == null ? RiskLevel.LOW : riskLevel;
        impact = impact == null
                ? new ImpactSummary(java.util.List.of(), "", "", true, 0)
                : impact;
        progress = progress < 0 ? -1 : Math.min(progress, 100);
        result = result == null ? Map.of() : Map.copyOf(result);
        revertOperationId = revertOperationId == null || revertOperationId.isBlank() ? null : revertOperationId;
        correlationId = correlationId == null ? "" : correlationId;
        actor = actor == null ? "" : actor;
        if (completedAt < 0) {
            completedAt = -1;
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isTerminal() {
        return status == OperationStatus.SUCCEEDED
                || status == OperationStatus.FAILED
                || status == OperationStatus.CANCELLED
                || status == OperationStatus.REVERTED
                || status == OperationStatus.TIMEOUT;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
