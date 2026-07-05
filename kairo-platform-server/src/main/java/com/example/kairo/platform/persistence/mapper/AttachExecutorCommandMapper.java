package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

public interface AttachExecutorCommandMapper {

    Map<String, Object> findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    int insertCommand(@Param("id") String id,
                      @Param("executorId") String executorId,
                      @Param("instanceId") String instanceId,
                      @Param("commandType") String commandType,
                      @Param("status") String status,
                      @Param("processId") String processId,
                      @Param("agentJar") String agentJar,
                      @Param("agentArgs") String agentArgs,
                      @Param("payloadJson") String payloadJson,
                      @Param("idempotencyKey") String idempotencyKey,
                      @Param("maxAttempts") int maxAttempts,
                      @Param("createdAt") Timestamp createdAt,
                      @Param("updatedAt") Timestamp updatedAt);

    Map<String, Object> findById(@Param("id") String id);

    int completeCommand(@Param("id") String id,
                        @Param("status") String status,
                        @Param("resultJson") String resultJson,
                        @Param("errorMessage") String errorMessage,
                        @Param("finishedAt") Timestamp finishedAt,
                        @Param("updatedAt") Timestamp updatedAt);

    int markInstanceAttached(@Param("id") Object id, @Param("updatedAt") Timestamp updatedAt);

    Map<String, Object> claimNext(@Param("executorId") String executorId,
                                  @Param("actor") String actor,
                                  @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                                  @Param("now") Timestamp now);

    int heartbeatExecutor(@Param("executorId") String executorId,
                          @Param("lastHeartbeatAt") Timestamp lastHeartbeatAt,
                          @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                          @Param("updatedAt") Timestamp updatedAt);

    int heartbeatTargets(@Param("executorId") String executorId,
                         @Param("lastSeenAt") Timestamp lastSeenAt,
                         @Param("updatedAt") Timestamp updatedAt);

    int heartbeatTargetInstances(@Param("executorId") String executorId,
                                 @Param("lastSeenAt") Timestamp lastSeenAt,
                                 @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                                 @Param("updatedAt") Timestamp updatedAt);
}
