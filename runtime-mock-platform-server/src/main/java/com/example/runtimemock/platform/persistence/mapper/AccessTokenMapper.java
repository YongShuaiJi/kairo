package com.example.runtimemock.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

public interface AccessTokenMapper {

    Map<String, Object> activeTokenByHash(@Param("tokenHash") String tokenHash);

    Map<String, Object> describeActiveTokenByHash(@Param("tokenHash") String tokenHash);

    int updateLastUsed(@Param("id") Object id, @Param("lastUsedAt") Timestamp lastUsedAt);

    int insertToken(@Param("id") String id,
                    @Param("tokenHash") String tokenHash,
                    @Param("subjectType") String subjectType,
                    @Param("subjectId") String subjectId,
                    @Param("displayName") String displayName,
                    @Param("createdBy") String createdBy,
                    @Param("createdAt") Timestamp createdAt,
                    @Param("expiresAt") Timestamp expiresAt);

    List<Map<String, Object>> listVisibleTokens();

    Map<String, Object> tokenSubject(@Param("id") String id);

    int renewToken(@Param("id") String id, @Param("expiresAt") Timestamp expiresAt);

    Map<String, Object> visibleToken(@Param("id") String id);

    int revokeToken(@Param("id") String id, @Param("revokedAt") Timestamp revokedAt);

    int deleteDifferentBootstrapToken(@Param("tokenHash") String tokenHash);

    int countBootstrapToken();

    int insertBootstrapToken(@Param("tokenHash") String tokenHash,
                             @Param("actor") String actor,
                             @Param("createdAt") Timestamp createdAt,
                             @Param("expiresAt") Timestamp expiresAt);

    List<Map<String, Object>> listUsers();

    Map<String, Object> userByUsername(@Param("username") String username);

    int activateBootstrapUser(@Param("id") String id,
                              @Param("username") String username,
                              @Param("displayName") String displayName);

    int countActiveAgent(@Param("subjectId") String subjectId);

    int countActiveUser(@Param("subjectId") String subjectId);

    int activateUserForToken(@Param("username") String username,
                             @Param("displayName") String displayName);

    int insertUserForToken(@Param("id") String id,
                           @Param("username") String username,
                           @Param("displayName") String displayName);

    int updateUserProfile(@Param("oldUsername") String oldUsername,
                          @Param("newUsername") String newUsername,
                          @Param("displayName") String displayName);

    int updateUserTokenSubject(@Param("oldUsername") String oldUsername,
                               @Param("newUsername") String newUsername,
                               @Param("displayName") String displayName);

    int revokeActiveUserTokens(@Param("username") String username,
                               @Param("revokedAt") Timestamp revokedAt);

    int deleteUserTokens(@Param("username") String username);

    int renewActiveUserTokens(@Param("username") String username,
                              @Param("expiresAt") Timestamp expiresAt);

    int deleteUserRoleBindings(@Param("userId") String userId);

    int deleteExternalIdentities(@Param("userId") String userId);

    int deleteUser(@Param("username") String username);
}
