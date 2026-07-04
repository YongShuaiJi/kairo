package com.example.runtimemock.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public interface AttachRegistrationMapper {

    Map<String, Object> findAttachExecutor(@Param("id") String id);

    int insertAttachExecutor(@Param("id") String id,
                             @Param("executorType") String executorType,
                             @Param("hostname") String hostname,
                             @Param("endpoint") String endpoint,
                             @Param("status") String status,
                             @Param("executorVersion") String executorVersion,
                             @Param("capabilitiesJson") String capabilitiesJson,
                             @Param("lastHeartbeatAt") Timestamp lastHeartbeatAt,
                             @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                             @Param("createdAt") Timestamp createdAt,
                             @Param("updatedAt") Timestamp updatedAt);

    int updateAttachExecutor(@Param("id") String id,
                             @Param("executorType") String executorType,
                             @Param("hostname") String hostname,
                             @Param("endpoint") String endpoint,
                             @Param("status") String status,
                             @Param("executorVersion") String executorVersion,
                             @Param("capabilitiesJson") String capabilitiesJson,
                             @Param("lastHeartbeatAt") Timestamp lastHeartbeatAt,
                             @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                             @Param("updatedAt") Timestamp updatedAt);

    Map<String, Object> findInstanceByProcessStartId(@Param("processStartId") String processStartId);

    int insertAttachTargetInstance(@Param("id") String id,
                                   @Param("applicationId") String applicationId,
                                   @Param("environmentId") String environmentId,
                                   @Param("nickname") String nickname,
                                   @Param("hostname") String hostname,
                                   @Param("processId") String processId,
                                   @Param("runtime") String runtime,
                                   @Param("status") String status,
                                   @Param("labelsJson") String labelsJson,
                                   @Param("lastSeenAt") Timestamp lastSeenAt,
                                   @Param("createdAt") Timestamp createdAt,
                                   @Param("updatedAt") Timestamp updatedAt,
                                   @Param("processStartId") String processStartId,
                                   @Param("javaVersion") String javaVersion,
                                   @Param("loadMode") String loadMode,
                                   @Param("capabilitiesJson") String capabilitiesJson,
                                   @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                                   @Param("registrationStatus") String registrationStatus);

    int updateAttachTargetInstance(@Param("id") String id,
                                   @Param("applicationId") String applicationId,
                                   @Param("environmentId") String environmentId,
                                   @Param("hostname") String hostname,
                                   @Param("processId") String processId,
                                   @Param("runtime") String runtime,
                                   @Param("lastSeenAt") Timestamp lastSeenAt,
                                   @Param("updatedAt") Timestamp updatedAt,
                                   @Param("javaVersion") String javaVersion,
                                   @Param("capabilitiesJson") String capabilitiesJson,
                                   @Param("leaseExpiresAt") Timestamp leaseExpiresAt);

    Map<String, Object> findSidecarByInstanceAndExecutor(@Param("instanceId") String instanceId,
                                                         @Param("executorId") String executorId);

    int insertAttachSidecar(@Param("id") String id,
                            @Param("instanceId") String instanceId,
                            @Param("executorId") String executorId,
                            @Param("status") String status,
                            @Param("sidecarVersion") String sidecarVersion,
                            @Param("endpoint") String endpoint,
                            @Param("capabilitiesJson") String capabilitiesJson,
                            @Param("lastHeartbeatAt") Timestamp lastHeartbeatAt,
                            @Param("createdAt") Timestamp createdAt,
                            @Param("updatedAt") Timestamp updatedAt);

    int updateAttachSidecar(@Param("id") String id,
                            @Param("executorId") String executorId,
                            @Param("sidecarVersion") String sidecarVersion,
                            @Param("endpoint") String endpoint,
                            @Param("capabilitiesJson") String capabilitiesJson,
                            @Param("lastHeartbeatAt") Timestamp lastHeartbeatAt,
                            @Param("updatedAt") Timestamp updatedAt);

    int upsertAttachExecutorTarget(@Param("executorId") String executorId,
                                   @Param("instanceId") String instanceId,
                                   @Param("processId") String processId,
                                   @Param("agentJar") String agentJar,
                                   @Param("runtime") String runtime,
                                   @Param("javaVersion") String javaVersion,
                                   @Param("status") String status,
                                   @Param("capabilitiesJson") String capabilitiesJson,
                                   @Param("lastSeenAt") Timestamp lastSeenAt,
                                   @Param("createdAt") Timestamp createdAt,
                                   @Param("updatedAt") Timestamp updatedAt);

    List<Map<String, Object>> findStaleAttachTargets(@Param("executorId") String executorId,
                                                     @Param("activeInstanceIds") List<String> activeInstanceIds);

    int countActiveAgentsByInstance(@Param("instanceId") Object instanceId,
                                    @Param("now") Timestamp now);

    int markAttachExecutorTargetOffline(@Param("executorId") String executorId,
                                        @Param("instanceId") Object instanceId,
                                        @Param("updatedAt") Timestamp updatedAt);

    int markSidecarsOfflineForTarget(@Param("executorId") String executorId,
                                     @Param("instanceId") Object instanceId,
                                     @Param("updatedAt") Timestamp updatedAt);

    int markSidecarsOfflineForOfflineTargets(@Param("executorId") String executorId,
                                             @Param("updatedAt") Timestamp updatedAt);
}
