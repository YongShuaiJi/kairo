package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

public interface TestPlatformMapper {

    void ensureDefaultProject();

    void ensureDefaultApplication();

    void ensureDefaultEnvironment();

    void insertSucceededRolloutExecution(@Param("id") String id,
                                         @Param("operationId") String operationId,
                                         @Param("instanceId") String instanceId);

    void markOperationSucceeded(@Param("operationId") String operationId);

    void enableRuleVersion(@Param("ruleId") String ruleId);

    void insertActiveRuleRuntimeStatus(@Param("id") String id,
                                       @Param("ruleId") String ruleId,
                                       @Param("instanceId") String instanceId);

    String ruleRuntimeStatusById(@Param("id") String id);

    String firstRolloutExecutionId(@Param("operationId") String operationId);

    String firstRolloutExecutionCommandId(@Param("operationId") String operationId);

    String ruleVersionStatus(@Param("ruleId") String ruleId,
                             @Param("version") int version);

    void expireAgentLease(@Param("agentId") String agentId);

    String operationPlanTerminalSource(@Param("operationId") String operationId);

    String ruleRuntimeStatus(@Param("ruleId") String ruleId,
                             @Param("instanceId") String instanceId);

    String operationPlanStatus(@Param("operationId") String operationId);

    String rolloutExecutionStatusByOperation(@Param("operationId") String operationId);

    void markOperationManuallyUnloaded(@Param("operationId") String operationId);

    void markAgentOfflineExpired(@Param("agentId") String agentId);

    void markInstanceOfflineExpired(@Param("instanceId") String instanceId);

    String instanceStatus(@Param("instanceId") String instanceId);

    void insertExpiredAttachExecutor();

    void insertExpiredAttachExecutorTarget(@Param("instanceId") String instanceId);

    void insertExpiredSidecar(@Param("instanceId") String instanceId);

    String attachExecutorStatus(@Param("executorId") String executorId);

    String attachExecutorTargetStatus(@Param("executorId") String executorId,
                                      @Param("instanceId") String instanceId);

    String sidecarStatus(@Param("sidecarId") String sidecarId);

    void insertEmptyRolloutOptionsApplication();

    void insertEmptyRolloutOptionsEnvironment();

    String devEnvironmentIdByApplication(@Param("applicationId") String applicationId);

    void markOtherAgentsOffline(@Param("agentId") String agentId);

    String instanceRegistrationStatus(@Param("instanceId") String instanceId);

    String lowerEnvironmentType(@Param("environmentId") String environmentId);

    int countRulesByName(@Param("name") String name);

    String activeUserTokenSubjectId(@Param("username") String username);
}
