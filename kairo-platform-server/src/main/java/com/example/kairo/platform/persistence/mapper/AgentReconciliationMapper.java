package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * V1.7 M1-D &sect;8.4: persistence for desired/actual reconciliation.
 *
 * <p>Reads the authoritative desired state for an instance (the per-instance
 * {@code rule_runtime_status} ledger joined to the formal {@code rule_version} and its
 * {@code rule_target}), the online agents a periodic sweep reconciles, and upserts/clears the
 * existing {@code degraded_class} marker that records a target the reconciler left untouched
 * because it was AHEAD/DIVERGED/TARGET_DRIFTED. The M1-C actual snapshot is read through
 * {@link AgentRuntimeStateMapper#findAgentRuntimeState}; the in-flight command guard uses
 * {@code AgentCommandMapper.commandByIdempotencyKey}. No new table is introduced.
 *
 * <p>Desired state comes only from formal, still-valid rule versions
 * ({@code rule_version.status = ENABLED}) recorded as {@code ACTIVE} on the instance. Trial,
 * expired or unpromoted script sessions never create a {@code rule_version} row and are therefore
 * structurally excluded from the recovery set (&sect;4.4 / &sect;8.4 item 7). A {@code REMOVED}
 * runtime status (or a {@code DISABLED} rule version) is the "desired EMPTY/REMOVED" signal that
 * drives a precise {@code RESET_CLASS} when the actual snapshot still carries the chain.
 */
public interface AgentReconciliationMapper {

    /**
     * One row per {@code rule_runtime_status} of the instance, joined to the formal
     * {@code rule_version} (its {@code status}, {@code script_hash}) and the first
     * {@code rule_target} (matching the rollout's {@code firstRuleTarget} order). Returns both
     * {@code ACTIVE} and {@code REMOVED} rows so the reconciler can distinguish a precise unload
     * (desired EMPTY/REMOVED) from an unknown actual chain (AHEAD).
     */
    List<Map<String, Object>> findInstanceRuntimeStatuses(@Param("instanceId") String instanceId);

    /** Online agents a periodic sweep reconciles: agent id, instance id, current process start id. */
    List<Map<String, Object>> findOnlineAgentsForReconciliation();

    /**
     * Mark a class degraded for an agent (AHEAD/DIVERGED/TARGET_DRIFTED). Upserts the existing
     * {@code degraded_class} row; a subsequent pass clears the marker via
     * {@link #deleteDegradedForAgent} when the target recovers.
     */
    int updateDegradedClass(@Param("agentId") String agentId,
                            @Param("className") String className,
                            @Param("reason") String reason,
                            @Param("now") Timestamp now);

    int insertDegradedClass(@Param("id") String id,
                            @Param("agentId") String agentId,
                            @Param("className") String className,
                            @Param("reason") String reason,
                            @Param("now") Timestamp now);

    /** Clear every degraded marker for an agent at the start of a pass (re-marked from the snapshot). */
    int deleteDegradedForAgent(@Param("agentId") String agentId);
}
