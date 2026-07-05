package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;

public interface BusinessIdMapper {

    int incrementSequence(@Param("resourceKey") String resourceKey, @Param("updatedAt") Timestamp updatedAt);

    int insertSequence(@Param("resourceKey") String resourceKey,
                       @Param("currentValue") long currentValue,
                       @Param("updatedAt") Timestamp updatedAt);

    Long currentSequence(@Param("resourceKey") String resourceKey);

    int updateSequenceValue(@Param("resourceKey") String resourceKey,
                            @Param("currentValue") long currentValue,
                            @Param("updatedAt") Timestamp updatedAt);

    int updateSequenceAtLeast(@Param("resourceKey") String resourceKey,
                              @Param("currentValue") long currentValue,
                              @Param("updatedAt") Timestamp updatedAt);
}
