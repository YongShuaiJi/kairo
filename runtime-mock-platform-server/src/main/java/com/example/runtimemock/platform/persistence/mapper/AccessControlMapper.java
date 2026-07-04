package com.example.runtimemock.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface AccessControlMapper {

    int countGlobalCapability(@Param("username") String username, @Param("capability") String capability);

    int countScopedCapability(@Param("username") String username,
                              @Param("capability") String capability,
                              @Param("resourceType") String resourceType,
                              @Param("resourceId") String resourceId);

    int countActiveUser(@Param("username") String username);

    int countSuperAdmin(@Param("username") String username);

    Map<String, Object> activeUser(@Param("username") String username);

    List<String> roles(@Param("username") String username);

    List<String> capabilities(@Param("username") String username);

    List<Map<String, Object>> scopes(@Param("username") String username);

    Map<String, Object> activeAgent(@Param("agentId") String agentId);

    List<String> agentCapabilities(@Param("agentId") String agentId);
}
