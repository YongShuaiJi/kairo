package com.example.kairo.platform.persistence.mapper;

import com.example.kairo.api.ScriptSessionStatus;
import com.example.kairo.platform.script.ScriptSessionRecord;
import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;

/** Persistence for {@link ScriptSessionRecord} rows. */
public interface ScriptSessionMapper {

    int insert(ScriptSessionRecord session);

    ScriptSessionRecord findById(@Param("id") String id);

    ScriptSessionRecord findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /**
     * Optimistic status transition: matches on {@code (id, expectedVersion)} and bumps version.
     * Returns 0 when the version no longer matches (concurrent transition).
     */
    int transition(@Param("id") String id,
                    @Param("status") ScriptSessionStatus status,
                    @Param("hitCount") long hitCount,
                    @Param("agentResultJson") String agentResultJson,
                    @Param("diagnosticsJson") String diagnosticsJson,
                    @Param("formalRuleId") String formalRuleId,
                    @Param("appliedAt") Timestamp appliedAt,
                    @Param("revertedAt") Timestamp revertedAt,
                    @Param("expectedVersion") long expectedVersion,
                    @Param("now") Timestamp now);

    /** Apply an agent ack: refresh hit count, agent result and diagnostics without changing status. */
    int applyAgentResult(@Param("id") String id,
                         @Param("hitCount") long hitCount,
                         @Param("agentResultJson") String agentResultJson,
                         @Param("diagnosticsJson") String diagnosticsJson,
                         @Param("expectedVersion") long expectedVersion,
                         @Param("now") Timestamp now);

    List<ScriptSessionRecord> listByApplication(@Param("applicationId") String applicationId);

    List<ScriptSessionRecord> listByAgent(@Param("agentId") String agentId);

    /** Non-terminal sessions whose deadline has passed; candidates for expiry compensation. */
    List<ScriptSessionRecord> findExpirable(@Param("now") Timestamp now);

    /**
     * The application id of the application the agent's instance belongs to, or null when the agent
     * does not exist. Used to validate that a session targets an agent in the declared application.
     */
    String findAgentApplication(@Param("agentId") String agentId);

    int countActiveByTarget(@Param("agentId") String agentId,
                            @Param("className") String className,
                            @Param("classLoaderId") String classLoaderId,
                            @Param("now") Timestamp now);
}
