package com.example.runtimemock.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface RuleLedgerQueryMapper {

    List<Map<String, Object>> pageRules(@Param("limit") int limit,
                                        @Param("offset") int offset,
                                        @Param("query") String query);

    long countRules(@Param("query") String query);

    Map<String, Object> ruleDetail(@Param("id") String id);

    List<Map<String, Object>> ruleVersions(@Param("ruleId") String ruleId);

    List<Map<String, Object>> ruleTargets(@Param("ruleId") String ruleId);

    List<Map<String, Object>> ruleCapabilities(@Param("ruleId") String ruleId);
}
