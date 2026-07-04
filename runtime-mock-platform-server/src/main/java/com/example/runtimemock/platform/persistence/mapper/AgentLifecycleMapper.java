package com.example.runtimemock.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

public interface AgentLifecycleMapper {

    int markAgentStopping(@Param("id") String id, @Param("updatedAt") Timestamp updatedAt);

    Map<String, Object> findInstance(@Param("id") String id);

    Map<String, Object> latestAgent(@Param("instanceId") String instanceId);

    Map<String, Object> latestExecutorSidecar(@Param("instanceId") String instanceId);

    Map<String, Object> latestStandaloneSidecar(@Param("instanceId") String instanceId);
}
