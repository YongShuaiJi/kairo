package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface TargetDiscoveryMapper {

    List<Map<String, Object>> activeAgents(@Param("applicationId") String applicationId,
                                           @Param("environmentId") String environmentId);
}
