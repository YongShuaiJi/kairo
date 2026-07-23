package com.example.kairo.api.snapshot;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * V1.7 M1-C &sect;8.3: one enhancement chain in a runtime-state snapshot. Carries the complete
 * target identity, the applied revision and canonical content hash (so the Platform can reconcile
 * desired &harr; actual), the JVM-bytecode transformation revision/hash (reconciled separately per
 * V1.4/V1.5), the desired state, the stable-sorted rule ids attached to the chain, and an optional
 * degraded reason.
 *
 * <p>This record never carries script source, raw class bytes, decompiled source or rule scripts:
 * only the rule ids and the hashes that the Platform needs for reconciliation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChainSnapshot(
        @JsonProperty("chainId") String chainId,
        @JsonProperty("className") String className,
        @JsonProperty("loaderId") String loaderId,
        @JsonProperty("methodName") String methodName,
        @JsonProperty("descriptor") String descriptor,
        @JsonProperty("location") String location,
        @JsonProperty("callSite") CallSiteSnapshot callSite,
        @JsonProperty("appliedRevision") long appliedRevision,
        @JsonProperty("canonicalHash") String canonicalHash,
        @JsonProperty("transformationRevision") long transformationRevision,
        @JsonProperty("transformationHash") String transformationHash,
        @JsonProperty("desiredState") String desiredState,
        @JsonProperty("ruleIds") List<String> ruleIds,
        @JsonProperty("degradedReason") String degradedReason) {
}
