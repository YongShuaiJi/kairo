package com.example.kairo.platform.command;

import com.example.kairo.api.ChainDesiredState;
import com.example.kairo.api.EnhancementLocation;
import com.example.kairo.api.InvokeOpcode;
import com.example.kairo.api.snapshot.AgentRuntimeSnapshot;
import com.example.kairo.api.snapshot.CallSiteSnapshot;
import com.example.kairo.api.snapshot.ChainSnapshot;
import com.example.kairo.api.snapshot.CollectionTruncation;
import com.example.kairo.api.snapshot.RuleSnapshot;
import com.example.kairo.api.snapshot.SnapshotBounds;
import com.example.kairo.api.snapshot.SnapshotTruncation;
import com.example.kairo.platform.persistence.mapper.AgentRuntimeStateMapper;
import com.example.kairo.platform.service.PlatformException;
import com.example.kairo.platform.service.PlatformJson;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * V1.7 M1-C &sect;8.3: validates and persists the Agent runtime-state snapshot carried in a
 * {@code REFRESH_RUNTIME_STATE} command ack. Invoked inside the existing ACK transaction (default
 * propagation), so a validation failure rolls the ACK back -- the command reverts to
 * {@code DISPATCHED} (never falsely ACKED) and no actual state is overwritten. M1-A lease/epoch
 * fencing and M1-B restart recovery are untouched: the snapshot step runs only after the guarded
 * ACK UPDATE succeeds, so it never re-runs for a duplicate ack.
 *
 * <p>Validation is strict and nested: it rejects unknown fields, null/malformed nested entries,
 * missing/blank required rule/chain/target/call-site fields, invalid enum/location/state values,
 * negative revisions/timestamps/counts, unsorted collections/ruleIds, inconsistent truncation
 * total/included/reason, a {@code byteLimit} that is not the fixed limit, a
 * {@code serializedBytes} that does not equal the actual deterministic serialized byte count, and
 * an included count that does not equal the actual list size. Empty collections remain valid.
 *
 * <p>Concurrent latest-snapshot replacement for the same agent is serialized: the agent's
 * registration row is locked ({@code SELECT ... FOR UPDATE}) for the duration of the ACK
 * transaction so two valid REFRESH acks cannot race the delete+insert or violate the primary key.
 * A snapshot whose {@code processStartId} does not equal the currently registered
 * {@code instance.process_start_id} is a late ACK from an old process and is rejected with a
 * conflict (no ACK, no overwrite). Only one latest bounded snapshot is persisted per agent
 * (delete + insert), never append-only history.
 */
@Component
public class AgentRuntimeStateExchange {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final AgentRuntimeStateMapper stateMapper;

    public AgentRuntimeStateExchange(AgentRuntimeStateMapper stateMapper) {
        this.stateMapper = stateMapper;
    }

    /**
     * Validate the snapshot in the ack {@code result} and persist the latest actual state for the
     * agent. Any validation failure throws {@link PlatformException} so the surrounding ACK
     * transaction rolls back.
     *
     * @param commandId the acked command id (for diagnostics)
     * @param command   the acked command row (carries agent_id and command_type)
     * @param result    the ack result map (the snapshot)
     * @param now       the platform's received-at instant
     */
    void validateAndPersist(String commandId, Map<String, Object> command,
                            Map<String, Object> result, Instant now) {
        String agentId = String.valueOf(command.get("agent_id"));
        String commandType = String.valueOf(command.get("command_type"));

        AgentRuntimeSnapshot snapshot = deserialize(result);
        byte[] bytes = PlatformJson.bytes(snapshot);
        validate(commandId, agentId, commandType, snapshot, bytes);

        // Serialize concurrent latest-snapshot replacement for the same agent: lock the agent's
        // registration row for the rest of the ACK transaction so two valid REFRESH acks cannot
        // race the delete+insert or violate the primary key.
        if (stateMapper.lockAgentInstance(agentId) == null) {
            throw PlatformException.conflict("AGENT_COMMAND_STATE_CONFLICT",
                    "runtime state snapshot rejected: agent is not registered",
                    conflictDetails(commandId, snapshot.processStartId(), null));
        }

        // The stale-process fence: the snapshot's processStartId must equal the id the Platform
        // currently has registered for this agent's instance. A mismatch means the snapshot is from
        // an old process; reject it (no ACK, no overwrite of current actual state).
        Map<String, Object> registration = stateMapper.findInstanceRegistrationByAgent(agentId);
        if (registration == null || registration.get("process_start_id") == null) {
            throw PlatformException.conflict("AGENT_COMMAND_STATE_CONFLICT",
                    "runtime state snapshot rejected: agent has no registered process_start_id",
                    conflictDetails(commandId, snapshot.processStartId(), null));
        }
        String currentProcessStartId = String.valueOf(registration.get("process_start_id"));
        if (!currentProcessStartId.equals(snapshot.processStartId())) {
            throw PlatformException.conflict("AGENT_COMMAND_STATE_CONFLICT",
                    "runtime state snapshot rejected: stale processStartId (snapshot from an old process)",
                    conflictDetails(commandId, snapshot.processStartId(), currentProcessStartId));
        }
        String instanceId = String.valueOf(registration.get("instance_id"));

        // Persist exactly one latest bounded snapshot per agent (replace within the ACK transaction).
        stateMapper.deleteAgentRuntimeState(agentId);
        stateMapper.insertAgentRuntimeState(agentId, instanceId, snapshot.processStartId(),
                snapshot.protocolVersion(), snapshot.agentVersion(),
                timestamp(snapshot.observedAt()), timestamp(now),
                snapshot.disabled(),
                size(snapshot.rules()), size(snapshot.chains()), size(snapshot.degradedClasses()),
                bytes.length, PlatformJson.write(snapshot), timestamp(now));
    }

    private AgentRuntimeSnapshot deserialize(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            throw fail("runtime state snapshot is missing");
        }
        try {
            return MAPPER.convertValue(result, AgentRuntimeSnapshot.class);
        } catch (RuntimeException e) {
            throw fail("runtime state snapshot is malformed: " + rootMessage(e));
        }
    }

    private void validate(String commandId, String agentId, String commandType,
                          AgentRuntimeSnapshot snapshot, byte[] bytes) {
        requireNonBlank(snapshot.protocolVersion(), "protocolVersion");
        requireNonBlank(snapshot.agentId(), "agentId");
        requireNonBlank(snapshot.processStartId(), "processStartId");
        requireNonBlank(snapshot.agentVersion(), "agentVersion");
        if (snapshot.rules() == null || snapshot.chains() == null
                || snapshot.degradedClasses() == null || snapshot.truncation() == null) {
            throw fail("runtime state snapshot is missing a required collection or truncation metadata");
        }
        if (!SnapshotBounds.PROTOCOL_VERSION.equals(snapshot.protocolVersion())) {
            throw fail("runtime state snapshot protocolVersion is not supported: "
                    + snapshot.protocolVersion());
        }
        if (!"REFRESH_RUNTIME_STATE".equals(commandType)) {
            throw fail("runtime state snapshot ack must be for a REFRESH_RUNTIME_STATE command");
        }
        if (!agentId.equals(snapshot.agentId())) {
            throw fail("runtime state snapshot agentId does not match the command's agent");
        }
        if (snapshot.observedAt() < 0) {
            throw fail("runtime state snapshot observedAt must be non-negative");
        }
        if (size(snapshot.rules()) > SnapshotBounds.MAX_RULES
                || size(snapshot.chains()) > SnapshotBounds.MAX_CHAINS
                || size(snapshot.degradedClasses()) > SnapshotBounds.MAX_DEGRADED_CLASSES) {
            throw fail("runtime state snapshot exceeds collection bounds");
        }
        if (bytes.length > SnapshotBounds.MAX_SERIALIZED_BYTES) {
            throw fail("runtime state snapshot exceeds the serialized byte limit");
        }

        validateRules(snapshot.rules());
        validateChains(snapshot.chains());
        validateDegraded(snapshot.degradedClasses());
        validateTruncation(snapshot.truncation(), snapshot, bytes);
    }

    private void validateRules(List<RuleSnapshot> rules) {
        String prev = null;
        for (int i = 0; i < rules.size(); i++) {
            RuleSnapshot rule = rules.get(i);
            if (rule == null) {
                throw fail("runtime state snapshot rule[" + i + "] is null");
            }
            requireNonBlank(rule.ruleId(), "rule[" + i + "].ruleId");
            if (rule.ruleVersion() < 0) {
                throw fail("runtime state snapshot rule[" + i + "].ruleVersion must be non-negative");
            }
            if (rule.expireAt() < 0) {
                throw fail("runtime state snapshot rule[" + i + "].expireAt must be non-negative");
            }
            if (prev != null && rule.ruleId().compareTo(prev) < 0) {
                throw fail("runtime state snapshot rules are not sorted by ruleId");
            }
            prev = rule.ruleId();
        }
    }

    private void validateChains(List<ChainSnapshot> chains) {
        String prevChainId = null;
        for (int i = 0; i < chains.size(); i++) {
            ChainSnapshot chain = chains.get(i);
            if (chain == null) {
                throw fail("runtime state snapshot chain[" + i + "] is null");
            }
            requireNonBlank(chain.chainId(), "chain[" + i + "].chainId");
            requireNonBlank(chain.className(), "chain[" + i + "].className");
            requireNonBlank(chain.loaderId(), "chain[" + i + "].loaderId");
            requireNonBlank(chain.methodName(), "chain[" + i + "].methodName");
            requireNonBlank(chain.descriptor(), "chain[" + i + "].descriptor");
            requireNonBlank(chain.location(), "chain[" + i + "].location");
            EnhancementLocation location = enumValue(EnhancementLocation.class, chain.location(),
                    "chain[" + i + "].location");
            requireNonBlank(chain.canonicalHash(), "chain[" + i + "].canonicalHash");
            requireNonNull(chain.transformationHash(), "chain[" + i + "].transformationHash");
            requireNonBlank(chain.desiredState(), "chain[" + i + "].desiredState");
            enumValue(ChainDesiredState.class, chain.desiredState(), "chain[" + i + "].desiredState");
            if (chain.appliedRevision() < 0) {
                throw fail("runtime state snapshot chain[" + i + "].appliedRevision must be non-negative");
            }
            if (chain.transformationRevision() < 0) {
                throw fail("runtime state snapshot chain[" + i + "].transformationRevision must be non-negative");
            }
            if (chain.ruleIds() == null) {
                throw fail("runtime state snapshot chain[" + i + "].ruleIds is null");
            }
            String prevRuleId = null;
            for (int j = 0; j < chain.ruleIds().size(); j++) {
                String ruleId = chain.ruleIds().get(j);
                if (ruleId == null || ruleId.isBlank()) {
                    throw fail("runtime state snapshot chain[" + i + "].ruleIds[" + j + "] is blank");
                }
                if (prevRuleId != null && ruleId.compareTo(prevRuleId) < 0) {
                    throw fail("runtime state snapshot chain[" + i + "].ruleIds are not sorted");
                }
                prevRuleId = ruleId;
            }
            // callSite must be present for call-site locations, absent otherwise.
            if (location.isCallSiteLocation()) {
                if (chain.callSite() == null) {
                    throw fail("runtime state snapshot chain[" + i + "] is missing callSite for a call-site location");
                }
            } else if (chain.callSite() != null) {
                throw fail("runtime state snapshot chain[" + i + "].callSite must be null for a non-call-site location");
            }
            if (chain.callSite() != null) {
                validateCallSite(chain.callSite(), i);
            }
            if (prevChainId != null && chain.chainId().compareTo(prevChainId) < 0) {
                throw fail("runtime state snapshot chains are not sorted by chainId");
            }
            prevChainId = chain.chainId();
        }
    }

    private void validateCallSite(CallSiteSnapshot callSite, int chainIndex) {
        requireNonBlank(callSite.owner(), "chain[" + chainIndex + "].callSite.owner");
        requireNonBlank(callSite.name(), "chain[" + chainIndex + "].callSite.name");
        requireNonBlank(callSite.descriptor(), "chain[" + chainIndex + "].callSite.descriptor");
        requireNonBlank(callSite.opcode(), "chain[" + chainIndex + "].callSite.opcode");
        enumValue(InvokeOpcode.class, callSite.opcode(), "chain[" + chainIndex + "].callSite.opcode");
        if (callSite.occurrenceIndex() < 0) {
            throw fail("runtime state snapshot chain[" + chainIndex + "].callSite.occurrenceIndex must be non-negative");
        }
    }

    private void validateDegraded(List<String> degraded) {
        String prev = null;
        for (int i = 0; i < degraded.size(); i++) {
            String name = degraded.get(i);
            if (name == null || name.isBlank()) {
                throw fail("runtime state snapshot degradedClasses[" + i + "] is blank");
            }
            if (prev != null && name.compareTo(prev) < 0) {
                throw fail("runtime state snapshot degradedClasses are not sorted");
            }
            prev = name;
        }
    }

    private void validateTruncation(SnapshotTruncation t, AgentRuntimeSnapshot snapshot, byte[] bytes) {
        if (t.byteLimit() != SnapshotBounds.MAX_SERIALIZED_BYTES) {
            throw fail("runtime state snapshot truncation.byteLimit must be the fixed limit");
        }
        if (t.serializedBytes() != bytes.length) {
            throw fail("runtime state snapshot truncation.serializedBytes does not match the actual serialized byte count");
        }
        if (t.serializedBytes() < 0 || t.serializedBytes() > t.byteLimit()) {
            throw fail("runtime state snapshot truncation.serializedBytes is out of range");
        }
        validateCollectionTruncation("rules", t.rules(), snapshot.rules().size(), SnapshotBounds.MAX_RULES);
        validateCollectionTruncation("chains", t.chains(), snapshot.chains().size(), SnapshotBounds.MAX_CHAINS);
        validateCollectionTruncation("degradedClasses", t.degradedClasses(),
                snapshot.degradedClasses().size(), SnapshotBounds.MAX_DEGRADED_CLASSES);
    }

    private void validateCollectionTruncation(String name, CollectionTruncation c, int actualSize, int max) {
        if (c == null) {
            throw fail("runtime state snapshot truncation." + name + " is missing");
        }
        if (c.total() < 0 || c.included() < 0) {
            throw fail("runtime state snapshot truncation." + name + " total/included must be non-negative");
        }
        if (c.included() > c.total()) {
            throw fail("runtime state snapshot truncation." + name + ".included exceeds total");
        }
        if (c.included() > max) {
            throw fail("runtime state snapshot truncation." + name + ".included exceeds the bound");
        }
        if (c.included() != actualSize) {
            throw fail("runtime state snapshot truncation." + name + ".included does not equal the actual list size");
        }
        String expected = expectedReason(c.total(), c.included(), max);
        if (!java.util.Objects.equals(expected, c.reason())) {
            throw fail("runtime state snapshot truncation." + name + ".reason is inconsistent"
                    + " (expected " + expected + ", got " + c.reason() + ")");
        }
    }

    /** The reason the builder would emit for (total, included, max); null when not truncated. */
    private static String expectedReason(int total, int included, int max) {
        if (included >= total) {
            return null;
        }
        int bound = Math.min(total, max);
        return included < bound
                ? SnapshotBounds.REASON_SERIALIZED_BYTE_LIMIT
                : SnapshotBounds.REASON_ENTRY_COUNT_LIMIT;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> enumType, String value, String field) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (RuntimeException e) {
            throw fail("runtime state snapshot " + field + " is not a valid " + enumType.getSimpleName()
                    + ": " + value);
        }
    }

    private static Map<String, Object> conflictDetails(String commandId, String snapshotProcessStartId,
                                                        String currentProcessStartId) {
        return new java.util.LinkedHashMap<>(Map.of(
                "commandId", commandId,
                "snapshotProcessStartId", String.valueOf(snapshotProcessStartId),
                "currentProcessStartId", currentProcessStartId == null ? "" : currentProcessStartId));
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw fail("runtime state snapshot is missing required field: " + field);
        }
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw fail("runtime state snapshot is missing required field: " + field);
        }
    }

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static PlatformException fail(String message) {
        return PlatformException.badRequest("INVALID_FIELD", message);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private static Timestamp timestamp(long epochMillis) {
        return new Timestamp(epochMillis);
    }

    private static Timestamp timestamp(Instant instant) {
        return Timestamp.from(instant);
    }
}
