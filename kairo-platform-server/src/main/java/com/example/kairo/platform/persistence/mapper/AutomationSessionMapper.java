package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for {@code automation_session} + {@code automation_session_resource}
 * (V1.6 V40 / &sect;4.1). {@code resultType="map"} + snake_case per convention.
 */
public interface AutomationSessionMapper {

    int insertSession(@Param("id") String id,
                      @Param("caller") String caller,
                      @Param("source") String source,
                      @Param("applicationId") String applicationId,
                      @Param("environmentId") String environmentId,
                      @Param("instanceId") String instanceId,
                      @Param("agentId") String agentId,
                      @Param("maxCapabilityProfile") String maxCapabilityProfile,
                      @Param("ttlMillis") long ttlMillis,
                      @Param("deadlineMillis") long deadlineMillis,
                      @Param("status") String status,
                      @Param("riskLevel") String riskLevel,
                      @Param("correlationId") String correlationId,
                      @Param("tokenId") String tokenId,
                      @Param("createdAt") Timestamp createdAt,
                      @Param("updatedAt") Timestamp updatedAt);

    Map<String, Object> findById(@Param("id") String id);

    int transition(@Param("id") String id,
                   @Param("status") String status,
                   @Param("riskLevel") String riskLevel,
                   @Param("cleanupResultJson") String cleanupResultJson,
                   @Param("updatedAt") Timestamp updatedAt,
                   @Param("expectedVersion") long expectedVersion);

    List<Map<String, Object>> listExpired(@Param("nowMillis") long nowMillis);

    int insertResource(@Param("id") String id,
                       @Param("sessionId") String sessionId,
                       @Param("resourceType") String resourceType,
                       @Param("resourceId") String resourceId,
                       @Param("reversible") boolean reversible,
                       @Param("createdAt") Timestamp createdAt);

    List<Map<String, Object>> listResources(@Param("sessionId") String sessionId);

    int countActiveByToken(@Param("tokenId") String tokenId);

    List<Map<String, Object>> listByStatus(@Param("status") String status);

    /**
     * Row-lock the platform_access_token row (SELECT ... FOR UPDATE) to serialize the
     * per-token automation-session count-then-insert (V1.6 acceptance safety). Must be
     * called inside a transaction; the lock is held until commit.
     */
    String lockToken(@Param("tokenId") String tokenId);
}
