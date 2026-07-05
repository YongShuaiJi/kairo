package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface AccessControlMapper {

    int countGlobalCapability(@Param("actor") String actor, @Param("capability") String capability);

    int countScopedCapability(@Param("actor") String actor,
                              @Param("capability") String capability,
                              @Param("resourceType") String resourceType,
                              @Param("resourceId") String resourceId);

    int countActiveUser(@Param("actor") String actor);

    int countSuperAdmin(@Param("actor") String actor);

    Map<String, Object> activeUser(@Param("actor") String actor);

    List<String> roles(@Param("actor") String actor);

    List<String> capabilities(@Param("actor") String actor);

    List<Map<String, Object>> scopes(@Param("actor") String actor);

    Map<String, Object> activeAgent(@Param("agentId") String agentId);

    List<String> agentCapabilities(@Param("agentId") String agentId);
}
