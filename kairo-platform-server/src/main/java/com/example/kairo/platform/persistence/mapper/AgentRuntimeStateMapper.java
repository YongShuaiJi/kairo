package com.example.kairo.platform.persistence.mapper;

import org.apache.ibatis.annotations.Param;

import java.sql.Timestamp;
import java.util.Map;

/**
 * V1.7 M1-C &sect;8.3: persistence for the latest bounded Agent runtime-state snapshot.
 *
 * <p>One row per {@code agent_id}, replaced (delete + insert inside the ACK transaction) on each
 * accepted {@code REFRESH_RUNTIME_STATE} ack. The instance registration lookup joins
 * {@code agent_instance} to {@code instance} so the snapshot validator can compare the snapshot's
 * {@code processStartId} against the currently registered {@code instance.process_start_id} and
 * reject a late ACK from an old process.
 */
public interface AgentRuntimeStateMapper {

    /**
     * Lock the agent's registration row for the duration of the ACK transaction so concurrent
     * REFRESH acks for the same agent serialize their latest-snapshot replacement.
     */
    Map<String, Object> lockAgentInstance(@Param("agentId") String agentId);

    /** The instance_id and process_start_id currently registered for the agent. */
    Map<String, Object> findInstanceRegistrationByAgent(@Param("agentId") String agentId);

    int deleteAgentRuntimeState(@Param("agentId") String agentId);

    int insertAgentRuntimeState(@Param("agentId") String agentId,
                               @Param("instanceId") String instanceId,
                               @Param("processStartId") String processStartId,
                               @Param("protocolVersion") String protocolVersion,
                               @Param("agentVersion") String agentVersion,
                               @Param("observedAt") Timestamp observedAt,
                               @Param("receivedAt") Timestamp receivedAt,
                               @Param("disabled") boolean disabled,
                               @Param("ruleCount") int ruleCount,
                               @Param("chainCount") int chainCount,
                               @Param("degradedClassCount") int degradedClassCount,
                               @Param("serializedBytes") int serializedBytes,
                               @Param("snapshotJson") String snapshotJson,
                               @Param("now") Timestamp now);

    /** The current persisted snapshot for the agent (for validation/read-back and later M1-D use). */
    Map<String, Object> findAgentRuntimeState(@Param("agentId") String agentId);
}
