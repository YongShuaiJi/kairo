package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for the unified {@code operation} + {@code operation_event}
 * tables (V1.6 V40 / &sect;5.1). Uses {@code resultType="map"} + snake_case
 * columns per platform convention.
 */
public interface OperationMapper {

    int insertOperation(@Param("id") String id,
                        @Param("operationType") String operationType,
                        @Param("status") String status,
                        @Param("resourceType") String resourceType,
                        @Param("resourceId") String resourceId,
                        @Param("riskLevel") String riskLevel,
                        @Param("impactJson") String impactJson,
                        @Param("progress") int progress,
                        @Param("resultJson") String resultJson,
                        @Param("errorJson") String errorJson,
                        @Param("revertOperationId") String revertOperationId,
                        @Param("automationSessionId") String automationSessionId,
                        @Param("agentCommandId") String agentCommandId,
                        @Param("correlationId") String correlationId,
                        @Param("actor") String actor,
                        @Param("idempotencyKey") String idempotencyKey,
                        @Param("createdAt") Timestamp createdAt,
                        @Param("updatedAt") Timestamp updatedAt);

    Map<String, Object> findById(@Param("id") String id);

    Map<String, Object> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    Map<String, Object> findByAgentCommandId(@Param("agentCommandId") String agentCommandId);

    List<Map<String, Object>> listByResource(@Param("resourceType") String resourceType,
                                             @Param("resourceId") String resourceId);

    List<Map<String, Object>> listBySession(@Param("sessionId") String sessionId);

    List<Map<String, Object>> listRecent(@Param("status") String status,
                                         @Param("limit") int limit);

    int transition(@Param("id") String id,
                   @Param("status") String status,
                   @Param("progress") int progress,
                   @Param("resultJson") String resultJson,
                   @Param("errorJson") String errorJson,
                   @Param("revertOperationId") String revertOperationId,
                   @Param("completedAt") Timestamp completedAt,
                   @Param("updatedAt") Timestamp updatedAt,
                   @Param("expectedVersion") long expectedVersion);

    int updateRisk(@Param("id") String id,
                   @Param("riskLevel") String riskLevel,
                   @Param("impactJson") String impactJson,
                   @Param("updatedAt") Timestamp updatedAt);

    int linkAgentCommand(@Param("id") String id,
                         @Param("agentCommandId") String agentCommandId,
                         @Param("updatedAt") Timestamp updatedAt);

    int insertEvent(@Param("id") String id,
                    @Param("operationId") String operationId,
                    @Param("sequence") long sequence,
                    @Param("eventType") String eventType,
                    @Param("actor") String actor,
                    @Param("detailJson") String detailJson,
                    @Param("occurredAt") Timestamp occurredAt);

    List<Map<String, Object>> listEvents(@Param("operationId") String operationId);

    Long nextEventSequence(@Param("operationId") String operationId);
}
