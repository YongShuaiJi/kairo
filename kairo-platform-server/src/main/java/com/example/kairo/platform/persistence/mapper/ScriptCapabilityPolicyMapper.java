package com.example.kairo.platform.persistence.mapper;

import com.example.kairo.platform.script.ScriptCapabilityPolicy;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** Persistence for {@link ScriptCapabilityPolicy} rows (platform and application scopes). */
public interface ScriptCapabilityPolicyMapper {

    ScriptCapabilityPolicy find(@Param("scope") String scope, @Param("applicationId") String applicationId);

    ScriptCapabilityPolicy findPlatform();

    ScriptCapabilityPolicy findApplication(@Param("applicationId") String applicationId);

    int insert(ScriptCapabilityPolicy policy);

    /** Optimistic update: matches on {@code (scope, applicationId, revision)} and bumps revision. */
    int update(@Param("policy") ScriptCapabilityPolicy policy, @Param("expectedRevision") long expectedRevision);

    List<ScriptCapabilityPolicy> listApplications(@Param("applicationId") String applicationId);
}
