package com.example.kairo.api.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * V1.7 M1-C &sect;8.3: a bounded, read-only snapshot of an Agent's in-memory runtime state,
 * captured at one consistent logical point in time and transported inside the durable
 * {@code REFRESH_RUNTIME_STATE} command ack. The snapshot is the actual-state input for JVM-restart
 * recovery and (in M1-D) desired/actual reconciliation; it is never a command to mutate the
 * enhancement engine.
 *
 * <p>The snapshot deliberately carries only stable rule/chain identity, revisions, hashes and
 * counts. It never includes script source, tokens, Authorization headers, raw class bytes,
 * decompiled source, command/event history or any unbounded diagnostic list. Every collection is
 * stable-sorted and bounded per {@link SnapshotBounds}; the {@link SnapshotTruncation} metadata
 * records exactly what was included and why.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentRuntimeSnapshot(
        @JsonProperty("protocolVersion") String protocolVersion,
        @JsonProperty("agentId") String agentId,
        @JsonProperty("processStartId") String processStartId,
        @JsonProperty("observedAt") long observedAt,
        @JsonProperty("agentVersion") String agentVersion,
        @JsonProperty("disabled") boolean disabled,
        @JsonProperty("chains") List<ChainSnapshot> chains,
        @JsonProperty("rules") List<RuleSnapshot> rules,
        @JsonProperty("degradedClasses") List<String> degradedClasses,
        @JsonProperty("truncation") SnapshotTruncation truncation) {
}
