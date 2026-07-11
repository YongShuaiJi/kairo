package com.example.kairo.platform.persistence.mapper;

import com.example.kairo.platform.service.BytecodeMetadataService.BytecodeMetadata;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface BytecodeMetadataMapper {

    int update(BytecodeMetadata metadata);

    int insert(BytecodeMetadata metadata);

    List<BytecodeMetadata> findByClassIdentity(@Param("runtimeInstanceId") String runtimeInstanceId,
                                               @Param("binaryClassName") String binaryClassName,
                                               @Param("classLoaderId") String classLoaderId);
}
