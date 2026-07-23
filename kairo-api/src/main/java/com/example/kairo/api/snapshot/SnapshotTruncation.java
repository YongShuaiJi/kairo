package com.example.kairo.api.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * V1.7 M1-C &sect;8.3: structured truncation metadata for a runtime-state snapshot. Carries the
 * per-collection truncation for rules, chains and degraded classes, plus the byte limit and the
 * final serialized byte count of the whole snapshot so the Platform (and later M1-D reconciliation)
 * can see exactly how the snapshot was bounded. The serialized byte count is measured over the
 * deterministic UTF-8 JSON form (stable-sorted keys), so it is reproducible on both sides.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SnapshotTruncation(
        @JsonProperty("rules") CollectionTruncation rules,
        @JsonProperty("chains") CollectionTruncation chains,
        @JsonProperty("degradedClasses") CollectionTruncation degradedClasses,
        @JsonProperty("byteLimit") long byteLimit,
        @JsonProperty("serializedBytes") long serializedBytes) {
}
