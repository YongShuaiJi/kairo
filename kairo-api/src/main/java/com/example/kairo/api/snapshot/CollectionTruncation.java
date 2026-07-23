package com.example.kairo.api.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * V1.7 M1-C &sect;8.3: truncation metadata for one bounded collection of a runtime-state
 * snapshot. {@code total} is the actual number of entries the Agent observed; {@code included}
 * is the number that survived the fixed entry-count limit and (if needed) the serialized-byte cap.
 * {@code reason} is {@code null} when the collection was not truncated, otherwise one of the
 * {@link SnapshotBounds} reason constants explaining why {@code included < total}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CollectionTruncation(
        @JsonProperty("total") int total,
        @JsonProperty("included") int included,
        @JsonProperty("reason") String reason) {
}
