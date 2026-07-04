package com.example.runtimemock.platform.persistence.mapper;

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
                                @Param("createdBy") String createdBy,
                                @Param("createdAt") Timestamp createdAt);

    List<Map<String, Object>> operationsForRule(@Param("ruleId") String ruleId,
                                                @Param("ruleVersion") Long ruleVersion);

    int markDeletionUnloading(@Param("id") String id,
                              @Param("updatedBy") String updatedBy,
                              @Param("updatedAt") Timestamp updatedAt);

    int markDeletionUnloadedWithoutAgents(@Param("id") String id,
                                          @Param("updatedBy") String updatedBy,
                                          @Param("updatedAt") Timestamp updatedAt);

    int markExecutionsUnloaded(@Param("operationPlanId") String operationPlanId,
                               @Param("finishedAt") Timestamp finishedAt,
                               @Param("updatedAt") Timestamp updatedAt);

    List<Map<String, Object>> ruleTarget(@Param("ruleId") Object ruleId,
                                         @Param("ruleVersion") Object ruleVersion);

    List<Map<String, Object>> activeAgentsForSuccessfulExecutions(@Param("operationPlanId") String operationPlanId);

    Map<String, Object> rollbackExecution(@Param("id") String id);
}
