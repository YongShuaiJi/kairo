package com.example.kairo.api.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * V1.7 M1-C &sect;8.3: the call-site selector of a call-site-targeted chain, carried in a
 * snapshot so the Platform can identify the exact invoke instruction the chain is attached to.
 * {@code null} (omitted from the wire) for non-call-site locations.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CallSiteSnapshot(
        @JsonProperty("owner") String owner,
        @JsonProperty("name") String name,
        @JsonProperty("descriptor") String descriptor,
        @JsonProperty("opcode") String opcode,
        @JsonProperty("occurrenceIndex") int occurrenceIndex,
        @JsonProperty("fingerprint") String fingerprint) {
}
