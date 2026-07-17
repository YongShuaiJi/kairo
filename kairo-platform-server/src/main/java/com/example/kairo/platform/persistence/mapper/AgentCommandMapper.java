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

    /**
     * V1.7 M1 / W1: atomically lease a pollable command. A {@code PENDING} command is always
     * claimable; a {@code DISPATCHED} command is claimable only once its lease has expired
     * ({@code lease_expires_at &lt;= dispatchedAt}), so a live lease can never be stolen and two
     * workers racing on the same expired lease cannot both win (the first to update moves the
     * lease into the future, the second matches zero rows). Bumps {@code attempts} so each
     * successful lease is a distinct dispatch epoch used as the ack fencing token.
     */
    int dispatchCommand(@Param("id") Object id,
                        @Param("dispatchedAt") Timestamp dispatchedAt,
                        @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                        @Param("updatedAt") Timestamp updatedAt);

    /**
     * V1.7 M1 / W1: record an agent ack only when the acker is the current lease owner. The
     * agent echoes the {@code attempts} epoch it polled as {@code expectedAttempts}; a stale
     * owner whose lease was reclaimed (a newer dispatch bumped {@code attempts}) matches zero
     * rows, so it cannot overwrite the new owner's in-progress or terminal state. A null
     * {@code expectedAttempts} (a V1.6 agent that does not echo the epoch) is accepted only on
     * the <em>first dispatch</em> ({@code attempts = 1}): at epoch 1 there has been exactly one
     * owner, so a late ack cannot belong to a different (stale) owner. After any redispatch
     * ({@code attempts &gt;= 2}) a null epoch is ambiguous and is fenced out -- the command is
     * retried again rather than risk a stale V1.6 owner overwriting the new owner's state. This
     * preserves the V1.6 first-dispatch wire contract while closing the post-redispatch hole.
     */
    int ackCommand(@Param("id") String id,
                   @Param("status") String status,
                   @Param("resultJson") String resultJson,
                   @Param("errorMessage") String errorMessage,
                   @Param("completedAt") Timestamp completedAt,
                   @Param("updatedAt") Timestamp updatedAt,
                   @Param("expectedAttempts") Long expectedAttempts);

    /**
     * V1.7 M1-A &sect;8.1: atomically terminate a dispatched command whose lease has expired and
     * that has exhausted {@code max_attempts}, so it can never linger invisibly in DISPATCHED.
     * Sets the terminal {@code FAILED} state with the fixed error code
     * {@code AGENT_COMMAND_MAX_ATTEMPTS_EXHAUSTED}. Scoped to one agent's poll (the M1-A trigger
     * for this state-machine transition); cross-restart reconciliation belongs to M1-B. A command
     * still under its live lease, or one that may still be re-dispatched ({@code attempts <
     * max_attempts}), is left untouched.
     */
    int expireExhaustedCommands(@Param("agentId") String agentId, @Param("now") Timestamp now);

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
