package com.example.runtimemock.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public interface RuleVersionLifecycleMapper {

    Map<String, Object> findRuleVersion(@Param("ruleId") String ruleId, @Param("version") long version);

    int disableRuleVersion(@Param("ruleId") String ruleId,
                           @Param("version") long version,
                           @Param("disabledAt") Timestamp disabledAt,
                           @Param("autoDeleteAt") Timestamp autoDeleteAt,
                           @Param("disabledFromStatus") String disabledFromStatus);

    int enableRuleVersion(@Param("ruleId") String ruleId,
                          @Param("version") long version,
                          @Param("status") String status);

    List<Map<String, Object>> latestEnabledRuleVersion(@Param("ruleId") String ruleId);

    int updateRuleVersionPointers(@Param("ruleId") String ruleId,
                                  @Param("version") long version,
                                  @Param("status") String status,
                                  @Param("updatedBy") String updatedBy,
                                  @Param("updatedAt") Timestamp updatedAt);

    int markRuleAggregateDisabled(@Param("ruleId") String ruleId,
                                  @Param("updatedBy") String updatedBy,
                                  @Param("updatedAt") Timestamp updatedAt);

    int countRuleInScope(@Param("ruleId") String ruleId,
                         @Param("applicationId") String applicationId,
                         @Param("environmentId") String environmentId);

    int countEnabledRuleInScope(@Param("ruleId") String ruleId,
                                @Param("applicationId") String applicationId,
                                @Param("environmentId") String environmentId);

    int countEnabledRuleVersion(@Param("ruleId") String ruleId, @Param("version") long version);

    int deleteExpiredCapabilities(@Param("now") Timestamp now);

    int deleteExpiredTargets(@Param("now") Timestamp now);

    int deleteExpiredRuntimeStatuses(@Param("now") Timestamp now);

    int deleteExpiredBindings(@Param("now") Timestamp now);

    int deleteExpiredRuleVersions(@Param("now") Timestamp now);

    int refreshRuleAggregatesWithVersions(@Param("updatedAt") Timestamp updatedAt);

    int deleteLocksWithoutVersions();

    int deleteRulesWithoutVersions();
}
