package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public interface AgentCommandMapper {

    List<Map<String, Object>> listCommands();

    int insertCommand(@Param("id") String id,
                      @Param("agentId") String agentId,
                      @Param("commandType") String commandType,
                      @Param("status") String status,
                      @Param("idempotencyKey") String idempotencyKey,
                      @Param("payloadJson") String payloadJson,
                      @Param("resultJson") String resultJson,
                      @Param("attempts") int attempts,
                      @Param("maxAttempts") long maxAttempts,
                      @Param("availableAt") Timestamp availableAt,
                      @Param("createdBy") String createdBy,
                      @Param("createdAt") Timestamp createdAt,
                      @Param("updatedAt") Timestamp updatedAt,
                      @Param("correlationId") String correlationId,
                      @Param("rollbackExecutionId") String rollbackExecutionId);

    List<Map<String, Object>> pollCandidates(@Param("agentId") String agentId, @Param("now") Timestamp now);

    int dispatchCommand(@Param("id") Object id,
                        @Param("dispatchedAt") Timestamp dispatchedAt,
                        @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                        @Param("updatedAt") Timestamp updatedAt);

    int ackCommand(@Param("id") String id,
                   @Param("status") String status,
                   @Param("resultJson") String resultJson,
                   @Param("errorMessage") String errorMessage,
                   @Param("completedAt") Timestamp completedAt,
                   @Param("updatedAt") Timestamp updatedAt);

    int updateAgentStatus(@Param("id") Object id,
                          @Param("status") String status,
                          @Param("updatedAt") Timestamp updatedAt);

    List<Map<String, Object>> commandsByRollbackExecution(@Param("rollbackExecutionId") String rollbackExecutionId);

    Map<String, Object> rollbackExecution(@Param("id") String id);

    int completeRollbackExecution(@Param("id") String id,
                                  @Param("status") String status,
                                  @Param("finishedAt") Timestamp finishedAt);

    int updateUnloadingOperationStatus(@Param("id") String id,
                                       @Param("status") String status,
                                       @Param("updatedBy") String updatedBy,
                                       @Param("updatedAt") Timestamp updatedAt);

    int markRuntimeStatusesRemovedForOperation(@Param("operationPlanId") String operationPlanId,
                                               @Param("updatedAt") Timestamp updatedAt);

    Map<String, Object> operationPlan(@Param("id") String id);

    List<Map<String, Object>> executionsByCommand(@Param("commandId") String commandId);

    int completeExecution(@Param("id") String id,
                          @Param("status") String status,
                          @Param("errorMessage") String errorMessage,
                          @Param("finishedAt") Timestamp finishedAt,
                          @Param("updatedBy") String updatedBy,
                          @Param("updatedAt") Timestamp updatedAt,
                          @Param("version") long version);

    Map<String, Object> rolloutExecution(@Param("id") String id);

    List<Map<String, Object>> executionsByOperation(@Param("operationPlanId") String operationPlanId);

    int completeRunningOperation(@Param("id") String id,
                                 @Param("status") String status,
                                 @Param("version") long version,
                                 @Param("updatedBy") String updatedBy,
                                 @Param("updatedAt") Timestamp updatedAt,
                                 @Param("currentVersion") long currentVersion);

    List<Map<String, Object>> successfulExecutions(@Param("operationPlanId") String operationPlanId);

    int updateRuleRuntimeStatusActive(@Param("ruleId") String ruleId,
                                      @Param("ruleVersion") long ruleVersion,
                                      @Param("instanceId") String instanceId,
                                      @Param("updatedAt") Timestamp updatedAt);

    int insertRuleRuntimeStatus(@Param("id") String id,
                                @Param("ruleId") String ruleId,
                                @Param("ruleVersion") long ruleVersion,
                                @Param("instanceId") String instanceId,
                                @Param("updatedAt") Timestamp updatedAt);

    int transitionOperationToUnloading(@Param("id") String id,
                                       @Param("updatedBy") String updatedBy,
                                       @Param("updatedAt") Timestamp updatedAt,
                                       @Param("version") long version);

    int insertRollbackExecution(@Param("id") String id,
                                @Param("operationPlanId") String operationPlanId,
                                @Param("reason") String reason,
                                @Param("createdBy") String createdBy,
                                @Param("createdAt") Timestamp createdAt);

    List<Map<String, Object>> activeAgentsForOperation(@Param("operationPlanId") String operationPlanId);

    int markRollbackSucceeded(@Param("id") String id, @Param("finishedAt") Timestamp finishedAt);

    int markUnloadingOperationUnloadedWithoutAgents(@Param("id") String id,
                                                   @Param("updatedBy") String updatedBy,
                                                   @Param("updatedAt") Timestamp updatedAt);

    Map<String, Object> operationResource(@Param("id") Object id);

    int countAgent(@Param("id") String id);

    /** The advertised capability JSON array for an agent (V1.7 M0 dispatch gate). */
    String findAgentCapabilities(@Param("agentId") String agentId);

    Map<String, Object> commandById(@Param("id") String id);

    Map<String, Object> commandByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}
