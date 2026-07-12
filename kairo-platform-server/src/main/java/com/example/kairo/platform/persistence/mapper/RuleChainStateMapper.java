package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * MyBatis mapper for the V1.4 rule-chain desired/actual state tables
 * (V38 migration). The desired state is written via optimistic CAS on
 * {@code revision} + {@code version} so concurrent publishes are fenced; the
 * instance state is upserted as each Agent reports its applied revision/hash.
 */
public interface RuleChainStateMapper {

    Map<String, Object> findDesiredState(@Param("applicationId") String applicationId,
                                         @Param("environmentId") String environmentId,
                                         @Param("agentId") String agentId,
                                         @Param("chainId") String chainId);

    int insertDesiredState(@Param("id") String id,
                           @Param("applicationId") String applicationId,
                           @Param("environmentId") String environmentId,
                           @Param("agentId") String agentId,
                           @Param("chainId") String chainId,
                           @Param("targetClassName") String targetClassName,
                           @Param("targetMethodName") String targetMethodName,
                           @Param("targetMethodDescriptor") String targetMethodDescriptor,
                           @Param("targetLocation") String targetLocation,
                           @Param("targetCallSiteSelectorJson") String targetCallSiteSelectorJson,
                           @Param("revision") long revision,
                           @Param("canonicalHash") String canonicalHash,
                           @Param("desiredState") String desiredState,
                           @Param("transformationRevision") long transformationRevision,
                           @Param("ruleEntriesJson") String ruleEntriesJson,
                           @Param("createdBy") String createdBy,
                           @Param("createdAt") Timestamp createdAt);

    int casDesiredRevision(@Param("id") String id,
                           @Param("expectedRevision") long expectedRevision,
                           @Param("newRevision") long newRevision,
                           @Param("newHash") String newHash,
                           @Param("newDesiredState") String newDesiredState,
                           @Param("newTransformationRevision") long newTransformationRevision,
                           @Param("newRuleEntriesJson") String newRuleEntriesJson,
                           @Param("updatedBy") String updatedBy,
                           @Param("updatedAt") Timestamp updatedAt,
                           @Param("version") long version);

    int upsertInstanceState(@Param("id") String id,
                            @Param("desiredStateId") String desiredStateId,
                            @Param("agentId") String agentId,
                            @Param("appliedRevision") long appliedRevision,
                            @Param("appliedHash") String appliedHash,
                            @Param("transformationRevision") long transformationRevision,
                            @Param("transformationHash") String transformationHash,
                            @Param("applyTime") Timestamp applyTime,
                            @Param("degradedReason") String degradedReason,
                            @Param("status") String status,
                            @Param("updatedAt") Timestamp updatedAt);

    Map<String, Object> findInstanceState(@Param("desiredStateId") String desiredStateId,
                                          @Param("agentId") String agentId);

    List<Map<String, Object>> desiredStatesForTarget(@Param("targetClassName") String targetClassName,
                                                     @Param("targetMethodName") String targetMethodName,
                                                     @Param("targetMethodDescriptor") String targetMethodDescriptor,
                                                     @Param("targetLocation") String targetLocation);

    int insertOperation(@Param("id") String id,
                        @Param("desiredStateId") String desiredStateId,
                        @Param("operationType") String operationType,
                        @Param("expectedRevision") long expectedRevision,
                        @Param("desiredRevision") long desiredRevision,
                        @Param("desiredHash") String desiredHash,
                        @Param("status") String status,
                        @Param("createdBy") String createdBy,
                        @Param("createdAt") Timestamp createdAt);

    int updateOperationStatus(@Param("id") String id,
                              @Param("status") String status,
                              @Param("resultHash") String resultHash,
                              @Param("errorMessage") String errorMessage,
                              @Param("updatedBy") String updatedBy,
                              @Param("updatedAt") Timestamp updatedAt,
                              @Param("version") long version);
}
