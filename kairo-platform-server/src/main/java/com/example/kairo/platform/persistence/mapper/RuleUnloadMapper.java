package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public interface RuleUnloadMapper {

    Map<String, Object> operation(@Param("id") String id);

    int transitionManualUnloading(@Param("id") String id,
                                  @Param("reason") String reason,
                                  @Param("updatedBy") String updatedBy,
                                  @Param("updatedAt") Timestamp updatedAt,
                                  @Param("status") String status,
                                  @Param("version") long version);

    int insertRollbackExecution(@Param("id") String id,
                                @Param("operationPlanId") String operationPlanId,
                                @Param("rollbackType") String rollbackType,
                                @Param("reason") String reason,
                                @Param("targetClassId") String targetClassId,
                                @Param("targetClassName") String targetClassName,
                                @Param("createdBy") String createdBy,
                                @Param("createdAt") Timestamp createdAt);

    List<Map<String, Object>> operationsForRule(@Param("ruleId") String ruleId,
                                                @Param("ruleVersion") Long ruleVersion);

    int markDeletionUnloading(@Param("id") String id,
                              @Param("updatedBy") String updatedBy,
                              @Param("updatedAt") Timestamp updatedAt);

    List<Map<String, Object>> ruleTarget(@Param("ruleId") Object ruleId,
                                         @Param("ruleVersion") Object ruleVersion);

    /**
     * V1.7 M1-E &sect;8.5: the single active agent for an instance (most recent heartbeat), or
     * null when the instance's agent is offline. Used per-instance during an unload so each
     * instance records its own outcome: a reachable agent gets a precise RESET_CLASS dispatched
     * (execution &rarr; UNLOADING) while an unreachable one is recorded OFFLINE_PENDING for
     * compensation on reconnect.
     */
    Map<String, Object> activeAgentForInstance(@Param("instanceId") String instanceId);

    Map<String, Object> rollbackExecution(@Param("id") String id);
}
