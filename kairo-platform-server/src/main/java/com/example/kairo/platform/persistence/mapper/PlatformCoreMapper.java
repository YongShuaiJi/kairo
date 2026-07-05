package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public interface PlatformCoreMapper {

    int ping();

    List<Map<String, Object>> list(@Param("table") String table, @Param("orderBy") String orderBy);

    List<Map<String, Object>> listFencingTokens();

    List<Map<String, Object>> listAgents();

    long count(@Param("table") String table);

    Map<String, Object> findById(@Param("table") String table, @Param("id") String id);

    int countById(@Param("table") String table, @Param("id") String id);

    int insertInstance(@Param("id") String id, @Param("applicationId") String applicationId,
                       @Param("environmentId") String environmentId, @Param("nickname") String nickname,
                       @Param("hostname") String hostname, @Param("processId") String processId,
                       @Param("runtime") String runtime, @Param("status") String status,
                       @Param("labelsJson") String labelsJson, @Param("now") Timestamp now);

    int updateInstanceNickname(@Param("id") String id, @Param("nickname") String nickname,
                               @Param("updatedAt") Timestamp updatedAt);

    List<Map<String, Object>> findInstanceForRegistration(@Param("instanceId") String instanceId,
                                                          @Param("processStartId") String processStartId);

    int insertRuntimeInstance(@Param("id") String id, @Param("applicationId") String applicationId,
                              @Param("environmentId") String environmentId, @Param("nickname") String nickname,
                              @Param("hostname") String hostname, @Param("processId") String processId,
                              @Param("runtime") String runtime, @Param("status") String status,
                              @Param("labelsJson") String labelsJson, @Param("now") Timestamp now,
                              @Param("processStartId") String processStartId,
                              @Param("jvmStartedAt") Timestamp jvmStartedAt,
                              @Param("javaVersion") String javaVersion, @Param("loadMode") String loadMode,
                              @Param("agentVersion") String agentVersion, @Param("capabilitiesJson") String capabilitiesJson,
                              @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                              @Param("registrationStatus") String registrationStatus);

    int updateRuntimeInstance(@Param("id") String id, @Param("applicationId") String applicationId,
                              @Param("environmentId") String environmentId, @Param("hostname") String hostname,
                              @Param("processId") String processId, @Param("runtime") String runtime,
                              @Param("now") Timestamp now, @Param("processStartId") String processStartId,
                              @Param("jvmStartedAt") Timestamp jvmStartedAt,
                              @Param("javaVersion") String javaVersion, @Param("loadMode") String loadMode,
                              @Param("agentVersion") String agentVersion, @Param("capabilitiesJson") String capabilitiesJson,
                              @Param("leaseExpiresAt") Timestamp leaseExpiresAt);

    List<Map<String, Object>> firstAgentByInstance(@Param("instanceId") String instanceId);

    int insertRuntimeAgent(@Param("id") String id, @Param("instanceId") String instanceId,
                           @Param("sidecarId") String sidecarId, @Param("status") String status,
                           @Param("agentVersion") String agentVersion, @Param("bootstrapVersion") String bootstrapVersion,
                           @Param("listenHost") String listenHost, @Param("listenPort") int listenPort,
                           @Param("tokenHash") String tokenHash, @Param("capabilitiesJson") String capabilitiesJson,
                           @Param("now") Timestamp now, @Param("leaseExpiresAt") Timestamp leaseExpiresAt);

    int updateRuntimeAgent(@Param("id") String id, @Param("sidecarId") String sidecarId,
                           @Param("agentVersion") String agentVersion, @Param("bootstrapVersion") String bootstrapVersion,
                           @Param("listenHost") String listenHost, @Param("listenPort") int listenPort,
                           @Param("capabilitiesJson") String capabilitiesJson,
                           @Param("now") Timestamp now, @Param("leaseExpiresAt") Timestamp leaseExpiresAt);

    int deleteAgentCapabilities(@Param("agentId") String agentId);

    String findProjectId(@Param("projectName") String projectName);

    int insertProject(@Param("id") String id, @Param("name") String name, @Param("createdAt") Timestamp createdAt);

    String findApplicationId(@Param("projectId") String projectId, @Param("name") String name);

    int insertApplication(@Param("id") String id, @Param("projectId") String projectId,
                          @Param("name") String name, @Param("createdAt") Timestamp createdAt);

    int countEnvironmentType(@Param("applicationId") String applicationId, @Param("type") String type);

    int insertEnvironment(@Param("id") String id, @Param("applicationId") String applicationId,
                          @Param("name") String name, @Param("type") String type,
                          @Param("createdAt") Timestamp createdAt);

    int assignInstanceEnvironment(@Param("id") String id, @Param("environmentId") String environmentId,
                                  @Param("updatedAt") Timestamp updatedAt);

    int insertSidecar(@Param("id") String id, @Param("instanceId") String instanceId,
                      @Param("status") String status, @Param("sidecarVersion") String sidecarVersion,
                      @Param("endpoint") String endpoint, @Param("capabilitiesJson") String capabilitiesJson,
                      @Param("now") Timestamp now);

    int deleteInstanceRuleRuntimeStatus(@Param("instanceId") Object instanceId);
    int deleteInstanceRuleBindings(@Param("instanceId") Object instanceId);
    int deleteInstanceLabels(@Param("instanceId") Object instanceId);
    int deleteInstanceAssetClaims(@Param("instanceId") Object instanceId);
    int deleteInstanceAttachTargets(@Param("instanceId") Object instanceId);
    int deleteInstanceSidecarsWithoutAgents(@Param("instanceId") Object instanceId);
    int archiveInstance(@Param("instanceId") Object instanceId, @Param("updatedAt") Timestamp updatedAt);
    int abandonExecutionsForInstance(@Param("instanceId") Object instanceId, @Param("now") Timestamp now);
    int abandonPlansForInstanceWithoutLiveTargets(@Param("instanceId") Object instanceId, @Param("now") Timestamp now);

    int insertManualAgent(@Param("id") String id, @Param("instanceId") String instanceId,
                          @Param("sidecarId") String sidecarId, @Param("status") String status,
                          @Param("agentVersion") String agentVersion, @Param("bootstrapVersion") String bootstrapVersion,
                          @Param("listenHost") String listenHost, @Param("listenPort") int listenPort,
                          @Param("tokenHash") String tokenHash, @Param("capabilitiesJson") String capabilitiesJson,
                          @Param("now") Timestamp now);

    int updateAgentHeartbeat(@Param("id") String id, @Param("status") String status,
                             @Param("now") Timestamp now, @Param("leaseExpiresAt") Timestamp leaseExpiresAt);

    int updateInstanceHeartbeatByAgent(@Param("agentId") String agentId, @Param("now") Timestamp now,
                                       @Param("leaseExpiresAt") Timestamp leaseExpiresAt);

    String instanceIdByAgent(@Param("agentId") String agentId);

    int insertAgentHeartbeat(@Param("id") String id, @Param("agentId") String agentId,
                             @Param("status") String status, @Param("metricsJson") String metricsJson,
                             @Param("receivedAt") Timestamp receivedAt);

    List<Map<String, Object>> agentGoneOperations(@Param("instanceId") String instanceId);
    int restoreAgentGoneOperation(@Param("id") String id, @Param("updatedBy") String updatedBy,
                                  @Param("updatedAt") Timestamp updatedAt, @Param("version") long version);
    int resetRestoredExecution(@Param("operationPlanId") String operationPlanId,
                               @Param("instanceId") String instanceId,
                               @Param("updatedBy") String updatedBy,
                               @Param("updatedAt") Timestamp updatedAt);

    int insertRule(@Param("id") String id, @Param("applicationId") String applicationId,
                   @Param("environmentId") String environmentId, @Param("name") String name,
                   @Param("status") String status, @Param("actor") String actor, @Param("now") Timestamp now);
    int updateRuleAfterVersion(@Param("id") String id, @Param("version") long version,
                               @Param("actor") String actor, @Param("updatedAt") Timestamp updatedAt);
    int countRuleVersions(@Param("ruleId") String ruleId);
    int countRuleVersionsIn(@Param("ruleId") String ruleId, @Param("versions") List<Long> versions);
    int deleteRuleCapabilities(@Param("ruleId") String ruleId);
    int deleteRuleTargets(@Param("ruleId") String ruleId);
    int deleteRuleRuntimeStatuses(@Param("ruleId") String ruleId);
    int deleteRuleBindings(@Param("ruleId") String ruleId);
    int deleteRuleLocks(@Param("ruleId") String ruleId);
    int deleteRuleVersions(@Param("ruleId") String ruleId);
    int deleteRule(@Param("ruleId") String ruleId);
    int deleteRuleCapabilitiesByVersions(@Param("ruleId") String ruleId, @Param("versions") List<Long> versions);
    int deleteRuleTargetsByVersions(@Param("ruleId") String ruleId, @Param("versions") List<Long> versions);
    int deleteRuleRuntimeStatusesByVersions(@Param("ruleId") String ruleId, @Param("versions") List<Long> versions);
    int deleteRuleBindingsByVersions(@Param("ruleId") String ruleId, @Param("versions") List<Long> versions);
    int deleteRuleVersionsByVersions(@Param("ruleId") String ruleId, @Param("versions") List<Long> versions);

    int insertOperationPlan(@Param("id") String id, @Param("applicationId") String applicationId,
                            @Param("environmentId") String environmentId, @Param("planType") String planType,
                            @Param("resourceType") String resourceType, @Param("resourceId") String resourceId,
                            @Param("resourceVersion") long resourceVersion, @Param("status") String status,
                            @Param("version") long version, @Param("strategyJson") String strategyJson,
                            @Param("actor") String actor, @Param("now") Timestamp now);

    String ruleName(@Param("id") String id);

    int transitionOperationPlan(@Param("id") String id, @Param("status") String status,
                                @Param("version") long version, @Param("updatedBy") String updatedBy,
                                @Param("updatedAt") Timestamp updatedAt,
                                @Param("expectedStatus") String expectedStatus,
                                @Param("expectedVersion") long expectedVersion);

    int insertLabel(@Param("id") String id, @Param("instanceId") String instanceId,
                    @Param("labelKey") String labelKey, @Param("labelValue") String labelValue,
                    @Param("createdAt") Timestamp createdAt);
    int insertAgentCapability(@Param("id") String id, @Param("agentId") String agentId,
                              @Param("capability") String capability, @Param("metadataJson") String metadataJson,
                              @Param("createdAt") Timestamp createdAt);
    int insertRuleVersion(@Param("id") String id, @Param("ruleId") String ruleId,
                          @Param("version") long version, @Param("status") String status,
                          @Param("riskLevel") String riskLevel, @Param("matcherJson") String matcherJson,
                          @Param("scriptHash") String scriptHash, @Param("scriptJson") String scriptJson,
                          @Param("governanceJson") String governanceJson,
                          @Param("createdBy") String createdBy, @Param("createdAt") Timestamp createdAt);
    int insertRuleTarget(@Param("id") String id, @Param("ruleVersionId") String ruleVersionId,
                         @Param("protocol") String protocol, @Param("className") String className,
                         @Param("methodName") String methodName, @Param("matcherJson") String matcherJson,
                         @Param("createdAt") Timestamp createdAt);
    int insertRuleCapability(@Param("id") String id, @Param("ruleVersionId") String ruleVersionId,
                             @Param("capability") String capability, @Param("createdAt") Timestamp createdAt);

    long maxRuleVersion(@Param("ruleId") String ruleId);
    int incrementScopedCounter(@Param("counterKey") String counterKey, @Param("updatedAt") Timestamp updatedAt);
    int insertScopedCounter(@Param("counterKey") String counterKey, @Param("currentValue") long currentValue,
                            @Param("updatedAt") Timestamp updatedAt);
    Long scopedCounterValue(@Param("counterKey") String counterKey);

    List<Map<String, Object>> validateEnvironment(@Param("applicationId") String applicationId,
                                                  @Param("environmentId") String environmentId);
    List<Map<String, Object>> latestSidecar(@Param("instanceId") String instanceId);
    List<Map<String, Object>> environmentByName(@Param("applicationId") String applicationId,
                                                @Param("environmentName") String environmentName);
    int countInstanceNickname(@Param("nickname") String nickname);

    int insertAudit(@Param("id") String id, @Param("occurredAt") Timestamp occurredAt,
                    @Param("actor") String actor, @Param("identitySource") String identitySource,
                    @Param("action") String action, @Param("resourceType") String resourceType,
                    @Param("resourceId") String resourceId, @Param("resourceVersion") long resourceVersion,
                    @Param("beforeHash") String beforeHash, @Param("afterHash") String afterHash,
                    @Param("previousRecordHash") String previousRecordHash, @Param("recordHash") String recordHash,
                    @Param("correlationId") String correlationId, @Param("ipAddress") String ipAddress,
                    @Param("device") String device, @Param("result") String result,
                    @Param("reason") String reason, @Param("detailsJson") String detailsJson);
    List<String> auditHashes();
}
