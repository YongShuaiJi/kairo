package com.example.runtimemock.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

public interface IdempotencyRecordMapper {

    Map<String, Object> activeRecord(@Param("idempotencyKey") String idempotencyKey);

    int insertRecord(@Param("idempotencyKey") String idempotencyKey,
                     @Param("actor") String actor,
                     @Param("requestHash") String requestHash,
                     @Param("responseStatus") int responseStatus,
                     @Param("responseJson") String responseJson,
                     @Param("createdAt") Timestamp createdAt,
                     @Param("expiresAt") Timestamp expiresAt);
}
