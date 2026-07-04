package com.example.runtimemock.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface PlatformQueryMapper {

    List<Map<String, Object>> page(@Param("resource") String resource,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset,
                                   @Param("search") String search,
                                   @Param("like") String like);

    long count(@Param("resource") String resource,
               @Param("search") String search,
               @Param("like") String like);

    Map<String, Object> detail(@Param("resource") String resource, @Param("id") String id);

    List<Map<String, Object>> latestAgentForInstance(@Param("instanceId") String instanceId);

    List<Map<String, Object>> latestAttachExecutorForInstance(@Param("instanceId") String instanceId);

    List<Map<String, Object>> recentAudits();

    List<Map<String, Object>> allAudits();

    long countAgentsTotal();

    long countAgentsOnline();

    long countInstancesTotal();

    long countInjectableInstancesOnline();

    long countRulesTotal();

    long countRulesActive();

    long countRolloutsRunning();

    List<Map<String, Object>> searchTargets(@Param("like") String like,
                                            @Param("applicationId") String applicationId,
                                            @Param("environmentId") String environmentId);
}
