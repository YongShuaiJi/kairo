package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

public interface FencingTokenMapper {

    int insertToken(@Param("id") String id,
                    @Param("resourceType") String resourceType,
                    @Param("resourceId") String resourceId,
                    @Param("purpose") String purpose,
                    @Param("token") String token,
                    @Param("sequence") long sequence,
                    @Param("owner") String owner,
                    @Param("status") String status,
                    @Param("leaseExpiresAt") Timestamp leaseExpiresAt,
                    @Param("createdAt") Timestamp createdAt,
                    @Param("correlationId") String correlationId);

    int consumeIssuedToken(@Param("resourceType") String resourceType,
                           @Param("resourceId") String resourceId,
                           @Param("token") String token,
                           @Param("owner") String owner,
                           @Param("now") Timestamp now);
}
