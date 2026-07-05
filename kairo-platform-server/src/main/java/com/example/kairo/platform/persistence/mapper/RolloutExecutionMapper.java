package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public interface RolloutExecutionMapper {

    List<Map<String, Object>> runningOperations();

    List<Map<String, Object>> executions(@Param("operationPlanId") String operationPlanId);

    List<Map<String, Object>> activeTargetInstances(@Param("applicationId") Object applicationId,
                                                    @Param("environmentId") Object environmentId);

    int insertTargetSnapshot(@Param("id") String id,
                             @Param("operationPlanId") String operationPlanId,
                             @Param("instanceId") String instanceId,
                             @Param("labelsJson") Object labelsJson,
                             @Param("capturedAt") Timestamp capturedAt,
                             @Param("instanceNickname") String instanceNickname,
                             @Param("applicationName") String applicationName,
                             @Param("environmentName") String environmentName,
                             @Param("javaVersion") String javaVersion,
                             @Param("agentVersion") String agentVersion,
                             @Param("loadMode") String loadMode,
                             @Param("processStartId") String processStartId,
                             @Param("instanceLastSeenAt") Object instanceLastSeenAt,
                             @Param("attachExecutorId") String attachExecutorId);

    int insertExecution(@Param("id") String id,
                        @Param("operationPlanId") String operationPlanId,
                        @Param("instanceId") String instanceId,
                        @Param("expectedRuleVersion") long expectedRuleVersion,
                        @Param("updatedBy") String updatedBy,
                        @Param("updatedAt") Timestamp updatedAt,
                        @Param("instanceNickname") String instanceNickname,
                        @Param("applicationName") String applicationName,
                        @Param("environmentName") String environmentName,
                        @Param("javaVersion") String javaVersion,
                        @Param("agentVersion") String agentVersion,
                        @Param("loadMode") String loadMode,
                        @Param("processStartId") String processStartId,
                        @Param("instanceLastSeenAt") Object instanceLastSeenAt,
                        @Param("attachExecutorId") String attachExecutorId);

    List<Map<String, Object>> activeAgentsByInstance(@Param("instanceId") String instanceId);

    int markExecutionWaitingAgent(@Param("id") String id,
                                  @Param("commandId") Object commandId,
                                  @Param("startedAt") Timestamp startedAt,
                                  @Param("updatedBy") String updatedBy,
                                  @Param("updatedAt") Timestamp updatedAt);

    Map<String, Object> execution(@Param("id") String id);

    Map<String, Object> ruleVersion(@Param("ruleId") String ruleId, @Param("version") long version);

    List<Map<String, Object>> firstRuleTarget(@Param("ruleVersionId") Object ruleVersionId);

    int waitForAgent(@Param("id") Object id, @Param("reason") String reason, @Param("updatedAt") Timestamp updatedAt);

    int failOperationWithoutTargets(@Param("id") Object id,
                                    @Param("updatedBy") String updatedBy,
                                    @Param("updatedAt") Timestamp updatedAt,
                                    @Param("version") long version);

    Map<String, Object> operation(@Param("id") Object id);
}
